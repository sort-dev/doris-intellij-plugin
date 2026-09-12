import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "dev.sort.doris"
version = "1.3.4"

val brikkSqlVersion = "0.13.0"
require(!providers.gradleProperty("b1.provider").isPresent) {
    "Use -Ptest.sqlTranspiler=installed|absent; SQL Transpiler is no longer a library provider"
}
val sqlTranspilerTestMode = providers.gradleProperty("test.sqlTranspiler").orNull
require(sqlTranspilerTestMode in listOf(null, "installed", "absent")) {
    "test.sqlTranspiler must be installed or absent"
}
val siblingPluginZips = listOf("trino", "duckdb").mapNotNull { dialect ->
    providers.gradleProperty("test.${dialect}PluginZip").orNull?.let { path ->
        val zip = file(path)
        require(zip.isFile) { "Missing $dialect plugin ZIP: $path" }
        dialect to zip
    }
}
val testPluginIds = buildList {
    add("com.intellij.database")
    add("dev.sort.doris-intellij-plugin")
    if (sqlTranspilerTestMode == "installed") add("dev.sort.sql-transpiler-intellij-plugin")
    siblingPluginZips.forEach { (dialect, _) -> add("dev.sort.$dialect-intellij-plugin") }
}.joinToString(",")

repositories {
    // Both the shared engine and function catalogs are published on Maven Central.
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    // Authoritative Doris grammar (standalone ANTLR CST parser), vendored from Doris source because
    // it is not published to any public Maven repo — see vendor/README.md for the exact Doris SHA
    // and rebuild steps. Its only runtime dep, antlr4-runtime, still comes from Maven Central.
    // Un-relocated for now; the plugin classloader is isolated. If antlr4-runtime ever clashes with
    // the platform's, switch to the proven shade-relocate of org.antlr.v4.runtime.
    implementation(files("vendor/lib/doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar"))
    implementation("org.antlr:antlr4-runtime:4.13.1")

    // Embed only the core engine and metadata, not verification libraries or database drivers.
    // The supported IDEs supply compatible Kotlin/serialization APIs; do not bundle duplicates.
    implementation("dev.brikk.house:brikk-sql-jvm:$brikkSqlVersion") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json-jvm")
    }
    implementation("dev.brikk.house:brikk-sql-metadata-jvm:$brikkSqlVersion") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json-jvm")
    }
    // Compile-time only: lets the compiler resolve the @Serializable types on the metadata classes.
    // NOT bundled (the platform provides kotlinx-serialization at runtime); no version conflict.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    intellijPlatform {
        // DataGrip 2026.1 (platform build 261). Doris users are on the 2026.x line; the 252 SQL API
        // (e.g. SqlFileElementType's package) is incompatible with 261. Remote SDK so any clone/CI
        // can build without a local IDE install.
        val localIde = providers.gradleProperty("doris.localIde")
        if (localIde.isPresent) local(localIde) else datagrip("2026.1.3")
        bundledPlugin("com.intellij.database")
        // Required transitively in the TEST runtime: the database plugin's intellij.json.backend
        // module dependency lives in the JSON plugin; without it com.intellij.database won't load
        // in unit tests and the DorisSQL language never registers.
        bundledPlugin("com.intellij.modules.json")
        // Companion only in the opt-in coexistence test lane, never a production library provider.
        if (sqlTranspilerTestMode == "installed") plugin("dev.sort.sql-transpiler-intellij-plugin:0.2.0")
        siblingPluginZips.forEach { (_, zip) -> localPlugin(zip) }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // The project configurable supplies its searchable name. Avoid launching a second IDE just
    // to index checkbox text; that can contend with an already-running DataGrip instance.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            // Compiled against build 261; the 252<->261 SQL API break means it must not load on 252
            // or earlier (it silently half-loads and leaves dead data-source shells). The old 261.*
            // pin existed for that break; 262 is now bridged in-code (DorisMetaCompat for the
            // BasicMetaModel/BasicMetaObject ctor change, BasePredicatesHelper for the
            // ObjectFormatterMode move — see COMPAT-262.md), verified against both generations via
            // ./gradlew verifyPlugin, so one artifact serves 261 and 262.
            sinceBuild = "261"
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        ides {
            // BOTH supported generations — the compat acceptance gate is zero compatibility
            // problems on each (COMPAT-262.md):
            // 261 (current line, what we compile against):
            create("DB", "2026.1.3") {}
            // 262 (2026.2 EAP that enumerated the breakages):
            create("IU", "262.8665.81") {}
        }
    }
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}

tasks {
    processResources {
        from(files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
    }
    // Stable artifact name (no version suffix) so install-from-disk always points at the same file.
    buildPlugin {
        archiveVersion = ""
    }

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    named<Test>("test") {
        useJUnit()
        // The light test fixture doesn't enable the database plugin by default; without it our
        // plugin (depends on com.intellij.database) is skipped and the DorisSQL language is absent.
        systemProperty("idea.load.plugins.id", testPluginIds)
        systemProperty("test.siblingPlugins", siblingPluginZips.joinToString(",") { it.first })

        // Gate 1 dual golden corpus (DorisGoldenCorpusTest): absolute paths to the SQL corpus and
        // the recorded golden trees. Passing -Pgolden.record=true flips the test into record mode.
        systemProperty("corpus.dir", layout.projectDirectory.dir("src/test/resources/corpus").asFile.absolutePath)
        systemProperty("golden.dir", layout.projectDirectory.dir("src/test/resources/golden").asFile.absolutePath)
        if (providers.gradleProperty("golden.record").isPresent) {
            systemProperty("golden.record", "true")
        }

        // Replay is ON by default since 0.5.0 (see DorisReplay). The suite's baseline stays flag-OFF
        // so the flag-off golden corpus / lenient-parity contracts keep meaning "the shipped fallback
        // path"; DorisReplayPocTest pins "true" per-test and restores "false" in tearDown.
        systemProperty("doris.replay.poc", "false")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

val verifyEmbeddedPipes by tasks.registering {
    group = "verification"
    description = "Checks the self-contained PIPE engine and notices in the distribution."
    val distribution = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val pluginJarName = tasks.named<org.gradle.jvm.tasks.Jar>("composedJar").flatMap { it.archiveFileName }
    val libraries = setOf(
        "brikk-sql-jvmMain-$brikkSqlVersion.jar", "brikk-sql-metadata-jvmMain-$brikkSqlVersion.jar",
        "antlr4-runtime-4.13.1.jar", "doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar",
    )
    val requiredNotices = listOf(
        "brikk-sql-jvm:$brikkSqlVersion", "brikk-sql-metadata-jvm:$brikkSqlVersion",
        "Toby Mao", "ANTLR", "Permission is hereby granted", "ClickHouse", "StarRocks",
    )
    dependsOn("buildPlugin")
    inputs.file(distribution)
    doLast {
        ZipFile(distribution.get().asFile).use { zip ->
            val jars = zip.entries().asSequence().filter { it.name.endsWith(".jar") }.toList()
            val expected = libraries + pluginJarName.get()
            check(jars.size == expected.size && jars.map { it.name.substringAfterLast('/') }.toSet() == expected) {
                "Unexpected bundled libraries: ${jars.map { it.name }}"
            }
            // The IDE's bundled JBR may be newer than the supported Java 21 minimum.
            for (library in jars) {
                JarInputStream(zip.getInputStream(library)).use { jar ->
                    while (true) {
                        val entry = jar.nextJarEntry ?: break
                        if (!entry.name.endsWith(".class")) continue
                        val header = jar.readNBytes(8)
                        check(header.size == 8) { "Truncated class: ${library.name}/${entry.name}" }
                        val major = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                        check(major <= 65) { "Java 21 incompatible bytecode $major: ${library.name}/${entry.name}" }
                    }
                }
            }
            val ownJar = jars.single { it.name.substringAfterLast('/') == pluginJarName.get() }
            val resources = mutableMapOf<String, String>()
            JarInputStream(zip.getInputStream(ownJar)).use { jar ->
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    if (entry.name in setOf("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/THIRD_PARTY_NOTICES.md", "META-INF/plugin.xml")) {
                        resources[entry.name] = jar.readBytes().toString(Charsets.UTF_8)
                    }
                }
            }
            check(resources.keys.size == 4) { "Missing packaged notices or descriptor: ${resources.keys}" }
            val notices = resources.getValue("META-INF/THIRD_PARTY_NOTICES.md")
            check(requiredNotices.all { it in notices }) { "Embedded engine notices are missing or stale" }
            check(!Regex("""<depends\b[^>]*>\s*dev\.sort\.sql-transpiler-intellij-plugin\s*</depends>""")
                .containsMatchIn(resources.getValue("META-INF/plugin.xml"))) { "SQL Transpiler is still a provider dependency" }
        }
        logger.lifecycle("Embedded PIPE libraries and distribution notices verified")
    }
}
tasks.named("check") { dependsOn(verifyEmbeddedPipes) }

// Separate workers/sandboxes verify independence from the optional companion product.
if (sqlTranspilerTestMode != null) {
    tasks.named<PrepareSandboxTask>("prepareTestSandbox") {
        sandboxSuffix.set("-test-pipes-$sqlTranspilerTestMode")
    }
    tasks.named<Test>("test") {
        systemProperty("test.sqlTranspiler", sqlTranspilerTestMode)
        if (sqlTranspilerTestMode == "installed") {
            // Fixtures flatten plugin classloaders. Exercise Doris's own core/metadata, not the
            // companion's private versions. Production plugins retain independent classloaders.
            classpath = classpath.filter {
                !(it.path.contains("/sql-transpiler-intellij-plugin/") &&
                    (it.name.startsWith("brikk-sql-jvm-") || it.name.startsWith("brikk-sql-metadata-jvm-")))
            }
        }
    }
}

if (providers.gradleProperty("test.pluginIsolation").orNull == "true") {
    val mainOutputs = sourceSets.main.get().output.files.map { it.absoluteFile }.toSet() +
        layout.buildDirectory.dir("instrumented/instrumentCode").get().asFile.absoluteFile
    tasks.named<Test>("test") {
        include("**/DorisPipesIsolationTest.class")
        systemProperty("test.pluginIsolation", "true")
        classpath = classpath.filter {
            it.absoluteFile !in mainOutputs &&
                !it.path.contains("/sql-transpiler-intellij-plugin/") &&
                !it.path.contains("/trino-intellij-plugin/") &&
                !it.path.contains("/duckdb-intellij-plugin/") &&
                !it.name.startsWith("doris-intellij-plugin-") &&
                !it.name.startsWith("doris-fe-sql-parser-") &&
                !it.name.startsWith("brikk-sql-") &&
                it.name != "antlr4-runtime-4.13.1.jar"
        }
        // Keep the platform fixture core-loaded, but load product distributions with their real
        // PluginClassLoaders. An argument provider overrides the Gradle plugin's worker defaults.
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Didea.force.use.core.classloader=true", "-Didea.use.core.classloader.for.plugin.path=false")
        })
    }
}

// 0.5.0: replay is ON by default (plain runIde = replay + catalogs, the shipping config).
// runIdeReplay kept as a historical alias; runIdeNoReplay is the escape-hatch sandbox.
val runIdeNoReplay by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgs("-Ddoris.replay.poc=false")
    }
}
// Historical alias (pre-0.5.0, when replay was opt-in):
val runIdeReplay by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgs("-Ddoris.replay.poc=true")
    }
}

val runIdeWithPsiViewer by intellijPlatformTesting.runIde.registering {
    plugins {
        plugin("PsiViewer", "252.23892.248")
    }
}

// Usage: ./gradlew runIdeFrozeOver — the froze-over integration config (v0.3 dogfooding):
// Route B replay ON on top of the (since-M10 default-on) multi-catalog model.
val runIdeFrozeOver by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgs("-Ddoris.replay.poc=true", "-Ddoris.catalogs.experimental=true")
    }
}

// M10: catalogs are ON BY DEFAULT — plain ./gradlew runIde is now the catalogs experience.
// runIdeCatalogs remains as an explicit alias (harmless; sets what is already the default).
val runIdeCatalogs by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgs("-Ddoris.catalogs.experimental=true")
    }
}

// The M10 escape hatch: the flat single-database model (pre-0.3.0 behaviour) for A/B comparison.
val runIdeNoCatalogs by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgs("-Ddoris.catalogs.experimental=false")
    }
}
