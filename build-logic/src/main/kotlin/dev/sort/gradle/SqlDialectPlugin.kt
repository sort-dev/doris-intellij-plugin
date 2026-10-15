package dev.sort.gradle

import java.io.File
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformRepositoriesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformTestingExtension
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

private val dialects = listOf("clickhouse", "doris", "duckdb", "trino", "sql-transpiler")
private val kotlinHosts = listOf("com.intellij.java", "org.jetbrains.kotlin")
private const val JSON = "com.intellij.modules.json"
private const val GIT = "Git4Idea"
private const val ISOLATION_TESTS = "**/*Isolation*Test.class"
private const val RUNTIME_ALIAS = "intellij.runtime."
private val platformSerialization = listOf("core", "json").flatMap { listOf("kotlinx-serialization-$it", "kotlinx-serialization-$it-jvm") }

class SqlDialectPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        pluginManager.apply("java")
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.intellij.platform")

        val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun version(alias: String) = catalog.findVersion(alias).get().requiredVersion

        val baseVersion = objects.property(String::class.java)
        afterEvaluate { baseVersion.set(project.version.toString()) }
        val release = providers.gradleProperty("release").map {
            require(it in setOf("true", "false")) { "release must be true or false" }
            it.toBoolean()
        }.orElse(false)
        val identity = providers.of(GitBuildIdentity::class.java) { parameters.directory.set(layout.projectDirectory) }
        val buildVersion = release.flatMap { isRelease ->
            if (isRelease) baseVersion else baseVersion.zip(identity) { base, git -> pluginBuildVersion(base, git, false) }
        }

        val dialect = name.removeSuffix("-intellij-plugin")
        val siblings = dialects - dialect
        val extension = extensions.create<SqlDialectPluginExtension>("sqlDialectPlugin").apply {
            pluginId.convention("dev.sort.$name")
            excludePlatformKotlin.convention(true)
            kotlinTests.convention(true)
            legalFiles.convention(listOf("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"))
        }

        val platformPlugins = extension.additionalBundledPlugins.map { listOf("com.intellij.database", JSON) + kotlinHosts + it }
        val kotlinMode = providers.gradleProperty("test.kotlin").orNull
        require(kotlinMode in setOf(null, "present", "absent")) { "test.kotlin must be present or absent" }
        val sqlTranspilerMode = providers.gradleProperty("test.sqlTranspiler").orNull
        require(sqlTranspilerMode in setOf(null, "installed", "absent")) { "test.sqlTranspiler must be installed or absent" }
        val pluginIsolation = providers.gradleProperty("test.pluginIsolation").orNull == "true"
        require(!pluginIsolation || kotlinMode != "present") { "Run plugin isolation and Kotlin host tests in separate builds" }
        val kotlinTests = when {
            pluginIsolation -> provider { false }
            kotlinMode != null -> provider { kotlinMode == "present" }
            else -> extension.kotlinTests
        }
        val siblingZips = siblings.mapNotNull { sibling ->
            val property = "test.${if (sibling == "sql-transpiler") "sqlTranspiler" else sibling}PluginZip"
            val explicit = providers.gradleProperty(property).orNull?.let(::file)
            val discover = pluginIsolation && !(sibling == "sql-transpiler" && sqlTranspilerMode != null)
            val zip = explicit ?: if (discover) {
                val worktree = rootDir.parentFile.takeIf { it.name == "$name-worktrees" }
                val checkoutRoot = worktree?.parentFile ?: rootDir.parentFile
                val checkouts = listOfNotNull(
                    worktree?.let { checkoutRoot.resolve("$sibling-intellij-plugin-worktrees/${rootDir.name}") },
                    checkoutRoot.resolve("$sibling-intellij-plugin"),
                )
                providers.of(LocalPluginZip::class.java) {
                    parameters.checkouts.set(checkouts.map { it.absolutePath })
                    parameters.pluginName.set("$sibling-intellij-plugin")
                }.orNull?.let(::file)
                    ?: error("Missing local $sibling plugin ZIP for isolation. Expected $sibling-intellij-plugin-*.zip in build/distributions. Run ./gradlew buildPlugin in ${checkouts.joinToString()} or set -P$property=<zip>. Isolation does not fall back to Marketplace.")
            } else null
            zip?.let { sibling to it }
        }.toMap()
        siblingZips.forEach { (sibling, zip) -> require(zip.isFile) { "Missing $sibling plugin ZIP: $zip" } }
        require(sqlTranspilerMode == null || "sql-transpiler" !in siblingZips) {
            "Use either -Ptest.sqlTranspilerPluginZip or -Ptest.sqlTranspiler, not both"
        }
        val sqlTranspilerPlugin = "dev.sort.sql-transpiler-intellij-plugin:${version("sql-transpiler-plugin")}"
            .takeIf { sqlTranspilerMode == "installed" && "sql-transpiler" in siblings }
        val installedSiblings = siblingZips.keys + listOfNotNull("sql-transpiler".takeIf { sqlTranspilerPlugin != null })
        val dialectZips = siblingZips.keys - "sql-transpiler"
        val sqlTranspilerProperty = if ("sql-transpiler" in siblingZips) "installed" else sqlTranspilerMode

        repositories.exclusiveContent {
            forRepositories(repositories.mavenLocal())
            filter { includeVersionByRegex("dev\\.brikk\\.house", ".*", ".*-SNAPSHOT") }
        }
        repositories.mavenCentral()
        (repositories as ExtensionAware).extensions.configure<IntelliJPlatformRepositoriesExtension> { defaultRepositories() }

        val excludePlatformKotlin = extension.excludePlatformKotlin
        val serialization = catalog.findLibrary("kotlinx-serialization-json").get()
        configurations.named("compileOnly") {
            dependencies.addAllLater(excludePlatformKotlin.zip(serialization) { excluded, library -> listOfNotNull(library.takeIf { excluded }) })
        }
        dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-test")
        dependencies.add("testImplementation", catalog.findLibrary("junit").get())
        dependencies.extensions.configure<IntelliJPlatformDependenciesExtension> {
            val localIde = providers.gradleProperty("$dialect.localIde")
            if (localIde.isPresent) local(localIde) else intellijIdea(version("intellij-sdk"))
            bundledPlugins(platformPlugins)
            sqlTranspilerPlugin?.let { plugin(it) }
            siblingZips.values.forEach { localPlugin(it) }
            testFramework(TestFrameworkType.Platform)
        }
        dependencies.components.all {
            if (id.group == "dev.brikk.house" && excludePlatformKotlin.get()) allVariants {
                withDependencies {
                    removeAll { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib" || it.group == "org.jetbrains.kotlinx" && it.name in platformSerialization }
                }
            }
        }

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
        }

        extensions.configure<IntelliJPlatformExtension> {
            buildSearchableOptions.set(false)
            pluginConfiguration {
                version.set(buildVersion)
                ideaVersion {
                    sinceBuild.set(version("intellij-since-build"))
                    untilBuild.set(version("intellij-until-build"))
                }
            }
            pluginVerification {
                failureLevel.set(
                    listOf(
                        FailureLevel.COMPATIBILITY_PROBLEMS,
                        FailureLevel.COMPATIBILITY_WARNINGS,
                        FailureLevel.INVALID_PLUGIN,
                        FailureLevel.MISSING_DEPENDENCIES,
                        FailureLevel.OVERRIDE_ONLY_API_USAGES,
                        FailureLevel.NON_EXTENDABLE_API_USAGES,
                        FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
                    ),
                )
                ides {
                    create(provider { version("intellij-verifier-ides").split(' ') })
                    create(extension.additionalVerifierIdes)
                }
            }
            publishing {
                token.set(providers.gradleProperty("intellijPlatformPublishingToken"))
            }
        }

        val testPluginIds = platformPlugins.zip(extension.pluginId) { bundled, id -> bundled - JSON + id }.zip(kotlinTests) { ids, kotlin ->
            ids.filter { kotlin || it !in kotlinHosts } + installedSiblings.map { "dev.sort.$it-intellij-plugin" }
        }
        val siblingPaths = siblings.map { "/$it-intellij-plugin/" }
        val sourceSets = extensions.getByType<SourceSetContainer>()
        sourceSets.configureEach {
            compileClasspath = compileClasspath.filter { entry ->
                siblingPaths.none { it in entry.path } || !(entry.name.startsWith("brikk-") || entry.name.startsWith("kotlin-stdlib"))
            }
        }
        val productFiles = files(
            sourceSets.named("main").map { it.output },
            layout.buildDirectory.dir("instrumented/instrumentCode"),
            sourceSets.named("test").map { it.output.resourcesDir!! },
            configurations.named("runtimeClasspath"),
        )
        val productJarPrefix = "$name-"
        val sandboxLibraries = "/$name/lib/"
        fun Test.configureSuite(extraPluginIds: List<String> = emptyList()) {
            useJUnit()
            systemProperty("java.awt.headless", "true")
            systemProperty("test.kotlin", if (kotlinTests.get()) "present" else "absent")
            systemProperty("test.pluginIsolation", pluginIsolation.toString())
            systemProperty("test.siblingPlugins", dialectZips.joinToString(","))
            sqlTranspilerProperty?.let { systemProperty("test.sqlTranspiler", it) }
            jvmArgumentProviders.add(CommandLineArgumentProvider {
                listOf("-Didea.load.plugins.id=" + (testPluginIds.get() + extraPluginIds).distinct().joinToString(","))
            })
            if (!kotlinTests.get()) exclude("**/brikk/**")
            if (pluginIsolation) {
                include(ISOLATION_TESTS)
                classpath = (classpath - productFiles).filter { entry ->
                    siblingPaths.none { it in entry.path } && sandboxLibraries !in entry.path &&
                        !entry.name.startsWith(productJarPrefix) && !entry.name.startsWith("brikk-")
                }
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                    listOf("-Didea.force.use.core.classloader=true", "-Didea.use.core.classloader.for.plugin.path=false")
                })
            } else {
                exclude(ISOLATION_TESTS)
                classpath = classpath.filter { entry ->
                    siblingPaths.none { it in entry.path } || !(entry.name.startsWith("brikk-") || entry.name.startsWith("kotlin-stdlib"))
                }
            }
        }

        val testing = extensions.getByType<IntelliJPlatformTestingExtension>()
        catalog.versionAliases.filter { it.startsWith(RUNTIME_ALIAS) }.forEach { alias ->
            val generation = alias.removePrefix(RUNTIME_ALIAS)
            testing.testIde.register("test$generation") {
                type.set(IntelliJPlatformType.IntellijIdea)
                version.set(catalog.findVersion(alias).get().requiredVersion)
                providers.gradleProperty("test.ide$generation").orNull?.let { localPath.set(file(it)) }
                testFramework(TestFrameworkType.Platform)
                plugins {
                    bundledPlugins(platformPlugins.map { (it + GIT).distinct() })
                    sqlTranspilerPlugin?.let { plugin(it) }
                    siblingZips.values.forEach { localPlugin(it) }
                }
                task { configureSuite(listOf(GIT)) }
            }
        }
        if (installedSiblings.isNotEmpty()) tasks.named<PrepareSandboxTask>("prepareTestSandbox") {
            sandboxSuffix.set("-test-" + installedSiblings.sorted().joinToString("-"))
        }

        val licenseFiles = files(extension.legalFiles)
        tasks.named<ProcessResources>("processResources") {
            from(licenseFiles) { into("META-INF") }
        }
        tasks.named<ProcessResources>("processTestResources") {
            from("src/main/resources/META-INF") {
                include("*-kotlin.xml")
                into("META-INF")
            }
        }
        tasks.withType<VerifyPluginTask>().configureEach {
            systemProperty("plugin.verifier.home.dir", layout.buildDirectory.dir("plugin-verifier-cache").get().asFile.absolutePath)
        }
        tasks.named<Test>("test") { configureSuite() }

        val buildPlugin = tasks.named<BuildPluginTask>("buildPlugin") {
            archiveBaseName.set(extension.pluginId.map { it.substringAfterLast('.') })
            archiveVersion.set(buildVersion)
        }
        val runtimeArtifacts = configurations.named("runtimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
        val embedded = runtimeArtifacts.map { artifacts ->
            artifacts.mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.displayName }
        }
        tasks.register<VerifyBundledSnapshotHashes>("verifyBundledSnapshotHashes") {
            group = "verification"
            description = "Explicitly compares bundled dev.brikk.house snapshots with Maven Local by SHA-256."
            distribution.set(buildPlugin.flatMap { it.archiveFile })
            snapshotPaths.set(runtimeArtifacts.map { artifacts ->
                artifacts.mapNotNull { artifact ->
                    val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
                    if (id.group != "dev.brikk.house" || !id.version.endsWith("-SNAPSHOT") || artifact.file.extension != "jar")
                        return@mapNotNull null
                    val classifier = artifact.file.nameWithoutExtension.substringAfter("-${id.version}", "")
                    artifact.file.name to "${id.group.replace('.', '/')}/${id.module}/${id.version}/${id.module}-${id.version}$classifier.jar"
                }.toMap()
            })
            mavenLocal.set(layout.dir(providers.systemProperty("maven.repo.local")
                .orElse(providers.systemProperty("user.home").map { "$it/.m2/repository" }).map { File(it) }))
        }
        val verifyDistribution = tasks.register<VerifyDistribution>("verifyDistribution") {
            group = "verification"
            description = "Checks the embedded libraries, bytecode levels, notices and descriptor of the plugin ZIP."
            distribution.set(buildPlugin.flatMap { it.archiveFile })
            expectedVersion.set(buildPlugin.flatMap { it.archiveVersion })
            pluginJar.set(tasks.named<Jar>("composedJar").flatMap { it.archiveFileName })
            embeddedLibraries.set(extension.embeddedLibraries)
            embeddedComponents.set(embedded)
            bytecodeCeilings.set(extension.bytecodeCeilings)
            legalFiles.from(licenseFiles)
            requiredNotices.set(extension.requiredNotices)
            siblingPluginIds.set(siblings.map { "dev.sort.$it-intellij-plugin" })
            internalApiPackages.set(extension.internalApiPackages)
            internalApiAllowedIn.set(extension.internalApiAllowedIn)
        }
        val verifyBuildLogic = tasks.register<VerifyBuildLogic>("verifyBuildLogic") {
            group = "verification"
            description = "Checks that build-logic matches the commit recorded in build-logic/VENDORED-FROM."
            directory.set(layout.projectDirectory.dir("build-logic"))
        }
        tasks.named("check") { dependsOn(verifyDistribution, verifyBuildLogic) }
        buildPlugin.configure { dependsOn(verifyBuildLogic) }
        tasks.named("assemble") { dependsOn(verifyBuildLogic) }
        val verifyRelease = tasks.register<VerifyRelease>("verifyRelease") {
            group = "verification"
            description = "Refuses snapshot libraries in a release version."
            pluginVersion.set(buildVersion)
            embeddedComponents.set(embedded)
        }
        tasks.named("publishPlugin") { dependsOn(verifyDistribution, verifyRelease) }

        val ideaVersion = extensions.getByType<IntelliJPlatformExtension>().pluginConfiguration.ideaVersion
        tasks.register<DogfoodZip>("dogfoodZip") {
            group = "intellij platform"
            description = "Copies the plugin ZIP to build/dogfood under its version, with a SHA-256 file."
            distribution.set(buildPlugin.flatMap { it.archiveFile })
            sinceBuild.set(ideaVersion.sinceBuild)
            untilBuild.set(ideaVersion.untilBuild)
            val path = buildPlugin.flatMap { it.archiveFileName }.map { "dogfood/$it" }
            archive.set(layout.buildDirectory.file(path))
            checksum.set(layout.buildDirectory.file(path.map { "$it.sha256" }))
        }
    }
}
