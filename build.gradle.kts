plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "dev.sort.doris"
version = "0.2.2"

repositories {
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

    intellijPlatform {
        // DataGrip 2026.1 (platform build 261). Doris users are on the 2026.x line; the 252 SQL API
        // (e.g. SqlFileElementType's package) is incompatible with 261. Remote SDK so any clone/CI
        // can build without a local IDE install.
        datagrip("2026.1.3")
        bundledPlugin("com.intellij.database")
        // Required transitively in the TEST runtime: the database plugin's intellij.json.backend
        // module dependency lives in the JSON plugin; without it com.intellij.database won't load
        // in unit tests and the DorisSQL language never registers.
        bundledPlugin("com.intellij.modules.json")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // We ship no custom settings UI, so skip the searchable-options index step (it launches a
    // headless IDE, which fails while DataGrip is open, and slows builds). JetBrains-recommended.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            // Compiled against build 261; the 252<->261 SQL API break means it must not load on other
            // generations. Pin to the 261 line so it never silently half-loads and leaves dead shells.
            sinceBuild = "261"
            untilBuild = "261.*"
        }
    }
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}

tasks {
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
        systemProperty("idea.load.plugins.id", "com.intellij.database,dev.sort.doris-intellij-plugin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

val runIdeWithPsiViewer by intellijPlatformTesting.runIde.registering {
    plugins {
        plugin("PsiViewer", "252.23892.248")
    }
}
