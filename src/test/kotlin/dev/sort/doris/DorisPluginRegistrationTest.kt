package dev.sort.doris

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.GenericJdbcIntrospectorGate
import dev.brikk.house.sql.intellij.completion.SqlCompletionContributor
import dev.brikk.house.sql.intellij.testing.PluginRegistrationContract

class DorisPluginRegistrationTest : BasePlatformTestCase() {
    fun testRegistrationContract() {
        PluginRegistrationContract("dev.sort.doris-intellij-plugin", "dev.sort.doris").check(project)
    }

    fun testCommonJdbcAndTestSupportUseAlignedArtifacts() {
        val version = System.getProperty("test.brikk.sql.intellijSupportVersion")
        for ((type, artifact) in listOf(
            SqlCompletionContributor::class.java to "brikk-sql-intellij-support",
            GenericJdbcIntrospectorGate::class.java to "brikk-sql-intellij-jdbc-support",
            PluginRegistrationContract::class.java to "brikk-sql-intellij-test-support",
        )) {
            val source = type.getResource("${type.simpleName}.class").toString()
            assertTrue(source, source.contains("$artifact-$version.jar"))
        }
    }
}
