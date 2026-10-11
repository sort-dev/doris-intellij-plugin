pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("build-logic/gradle/libs.versions.toml"))
            version("antlr", "4.13.1")
            version("sql-transpiler-plugin", "0.2.0")
            version("brikk-sql-intellij-support", "0.1.2-SNAPSHOT")
        }
    }
}

rootProject.name = "doris-intellij-plugin"
