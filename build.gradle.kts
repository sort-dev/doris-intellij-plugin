plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "dev.sort.doris"
version = "0.3.0"

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
            ide("DB", "2026.1.3")
            // 262 (2026.2 EAP that enumerated the breakages):
            ide("IU", "262.8665.81")
        }
    }
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
    pluginVerification {
        ides {
            // 2026.2 EAP — enumerate upcoming-platform breakages ahead of the 262 release
            // (Marketplace verifier flagged 2 compatibility problems; untilBuild fences us to 261.*)
            ide("IU", "262.8665.81")
        }
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

        // Gate 1 dual golden corpus (DorisGoldenCorpusTest): absolute paths to the SQL corpus and
        // the recorded golden trees. Passing -Pgolden.record=true flips the test into record mode.
        systemProperty("corpus.dir", layout.projectDirectory.dir("src/test/resources/corpus").asFile.absolutePath)
        systemProperty("golden.dir", layout.projectDirectory.dir("src/test/resources/golden").asFile.absolutePath)
        if (providers.gradleProperty("golden.record").isPresent) {
            systemProperty("golden.record", "true")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Usage: ./gradlew runIdeReplay   (Route B replay ON). Plain ./gradlew runIde stays flag OFF = shipped.
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
