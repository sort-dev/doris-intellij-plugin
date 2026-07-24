plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "dev.sort.doris"
version = "1.0.0"

repositories {
    // brikk-sql-metadata (function catalogs) is a released artifact on Maven Central — no extra
    // repository or authentication needed.
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

    // brikk-sql-metadata: the featherweight (128 KB) function-catalog contract — DORIS_FUNCTION_CATALOG
    // (names, aliases, kind, overloads, isTableFunction, sinceVersion). Bundle ONLY this jar; exclude
    // its transitives (kotlin-stdlib + kotlinx-serialization core/json) because the IntelliJ platform
    // already ships them at runtime (verified in the 261 and 262 lib/ dirs), so bundling them would
    // add ~1.5 MB for nothing. See IDEAS-brikk-integration.md.
    implementation("dev.brikk.house:brikk-sql-metadata-jvm:0.6.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    // Compile-time only: lets the compiler resolve the @Serializable types on the metadata classes.
    // NOT bundled (the platform provides kotlinx-serialization at runtime); no version conflict.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

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
        // PATH B: the brikk-sql ENGINE comes from the published transpiler plugin — compile-time
        // visibility + sandbox/test presence via the Marketplace coordinate; at runtime the
        // optional <depends> in plugin.xml wires its classloader when the user has it installed.
        // The Doris plugin itself stays engine-free (metadata-only), per IDEAS §2/§3.
        plugin("dev.sort.sql-transpiler-intellij-plugin:0.2.0")
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

        // Replay is ON by default since 0.5.0 (see DorisReplay). The suite's baseline stays flag-OFF
        // so the flag-off golden corpus / lenient-parity contracts keep meaning "the shipped fallback
        // path"; DorisReplayPocTest pins "true" per-test and restores "false" in tearDown.
        systemProperty("doris.replay.poc", "false")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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
