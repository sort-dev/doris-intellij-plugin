plugins {
    id("dev.sort.sql-dialect-plugin")
}

group = "dev.sort.doris"
version = "1.5.8"

require(!providers.gradleProperty("b1.provider").isPresent) {
    "Use -Ptest.sqlTranspiler=installed|absent; SQL Transpiler is no longer a library provider"
}

sqlDialectPlugin {
    kotlinTests = false
    additionalBundledPlugins = listOf("Git4Idea")
    embeddedLibraries = with(libs.versions) {
        setOf(
            "brikk-sql-jvmMain-${brikk.sql.core.get()}.jar", "brikk-sql-metadata-jvmMain-${brikk.sql.core.get()}.jar",
            "brikk-sql-intellij-support-${brikk.sql.intellij.support.get()}.jar",
            "brikk-engine-kotlin-intellij-support-${brikk.engine.kotlin.intellij.support.get()}.jar",
            "antlr4-runtime-${antlr.get()}.jar", "doris-fe-sql-parser-4.1.4-gad35a140c7fd.jar",
        )
    }
    requiredNotices = with(libs.versions) {
        listOf(
            "brikk-sql-jvm:${brikk.sql.core.get()}", "brikk-sql-metadata-jvm:${brikk.sql.core.get()}",
            "Toby Mao", "ANTLR", "Permission is hereby granted", "ClickHouse", "StarRocks",
            "brikk-engine-kotlin-intellij-support:${brikk.engine.kotlin.intellij.support.get()}",
            "brikk-sql-intellij-support:${brikk.sql.intellij.support.get()}",
        )
    }
}

dependencies {
    testImplementation(libs.brikk.sql.intellij.test.support)
    implementation(libs.brikk.sql.intellij.support)
    implementation(libs.brikk.engine.kotlin.intellij.support)
    implementation(libs.brikk.sql.jvm)
    implementation(libs.brikk.sql.metadata.jvm)
    implementation(libs.antlr4.runtime)
    implementation(files("vendor/lib/doris-fe-sql-parser-4.1.4-gad35a140c7fd.jar"))
}

tasks.withType<Test>().configureEach {
    exclude("**/BrikkDorisGeneratedShapeTest.class")
    exclude(
        "**/DorisPipesActionTest\$PeerAction.class", "**/DorisPipesActionWiringTest\$PeerAction.class",
        "**/DorisPipesRunQueryAction.class", "**/DorisPipesRunSelectionAction.class",
    )
    systemProperty("corpus.dir", layout.projectDirectory.dir("src/test/resources/corpus").asFile.absolutePath)
    systemProperty("golden.dir", layout.projectDirectory.dir("src/test/resources/golden").asFile.absolutePath)
    if (providers.gradleProperty("golden.record").isPresent) {
        systemProperty("golden.record", "true")
    }
    systemProperty("doris.replay.poc", "false")
    systemProperty("test.sqlTranspiler.coreArtifact", if (providers.gradleProperty("test.sqlTranspilerPluginZip").isPresent) "jvmMain-0.19.1" else "jvm-0.6.0")
}

tasks.buildPlugin {
    archiveVersion = project.version.toString()
}

intellijPlatformTesting.testIde.register("testBrikkGenerated") {
    localPath = layout.dir(providers.gradleProperty("test.brikk.generatedIde").map { file(it) })
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    plugins {
        bundledPlugins("com.intellij.database", "com.intellij.java", "org.jetbrains.kotlin", "com.intellij.modules.json", "Git4Idea")
    }
    task {
        useJUnit()
        setIncludes(setOf("**/BrikkDorisGeneratedShapeTest.class"))
        setExcludes(emptySet())
        systemProperty("java.awt.headless", "true")
        systemProperty("idea.load.plugins.id", "com.intellij.database,dev.sort.doris-intellij-plugin,Git4Idea,com.intellij.java,org.jetbrains.kotlin")
        systemProperty("brikk.fixture.compiler.plugin", providers.gradleProperty("test.brikk.compilerPlugin").getOrElse(""))
    }
}

val runIdeNoReplay by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgs("-Ddoris.replay.poc=false")
    }
}

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

val runIdeFrozeOver by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgs("-Ddoris.replay.poc=true", "-Ddoris.catalogs.experimental=true")
    }
}

val runIdeCatalogs by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgs("-Ddoris.catalogs.experimental=true")
    }
}

val runIdeNoCatalogs by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgs("-Ddoris.catalogs.experimental=false")
    }
}
