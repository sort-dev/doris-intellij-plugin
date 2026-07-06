plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "dev.sort.doris"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))

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
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    named<Test>("test") {
        useJUnitPlatform()
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
