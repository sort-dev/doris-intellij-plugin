pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("build-logic/gradle/libs.versions.toml"))
            version("antlr", "4.13.2")
            version("sql-transpiler-plugin", "0.2.0")
            version("brikk-sql-core", "0.20.0-SNAPSHOT")
            version("brikk-sql-intellij-support", "0.2.0-SNAPSHOT")
        }
    }
}

rootProject.name = "doris-intellij-plugin"
