package dev.sort.doris

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.validation.DataSourceProblem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Consumer

class DorisConfigValidatorTest : BasePlatformTestCase() {
    private val validator = DorisConfigValidator()

    fun testSupportedConnectorJAuthoritiesDoNotReportMissingHostOrInvalidUrl() {
        val urls = listOf(
            "jdbc:mysql://fe1:9030/db",
            "jdbc:mysql://fe1:9030,fe2:9030/db",
            "jdbc:mysql:loadbalance://fe1:9030,fe2:9030/db",
            "jdbc:mysql:replication://(host=fe1,port=9030,type=source),(host=fe2,port=9030,type=replica)/db",
            "jdbc:mysql://(host=fe1,port=9030),(host=fe2,port=9030)/db",
            "jdbc:mysql://address=(host=fe1)(port=9030),address=(host=fe2)(port=9030)/db",
            "jdbc:mysql://address= (host=fe1) (port=9030)/db",
            "jdbc:mysql://(host=fe1,port=%39%30%33%30)/db",
            "jdbc:mysql://address=(host=fe1)(port=%39%30%33%30)/db",
            "jdbc:mysql://[2001:db8::1]:9030,[2001:db8::2]:9030/db",
            "jdbc:mysql://user:password@[fe1:9030,fe2:9030]/db",
            "jdbc:mysql://fe1:9030/db?sessionVariables=a,b&useSSL=false",
            "jdbc:mysql://u:p@fe1:9030,u:p@fe2:9030/db",
        )
        for (url in urls) {
            val ids = problemIds(url)
            assertFalse("$url -> $ids", "doris.url.invalid" in ids)
            assertFalse("$url -> $ids", "doris.host.missing" in ids)
            assertFalse("$url -> $ids", "doris.url.prefix" in ids)
            assertFalse("$url -> $ids", "doris.port.missing" in ids)
        }
    }

    fun testPortWarningsAggregateAcrossEndpointsAndGlobalProperties() {
        assertEquals(setOf("doris.port.mysql", "doris.port.missing"),
            problemIds("jdbc:mysql://fe1:3306,fe2/db"))
        assertEquals(setOf("doris.port.missing"), problemIds("jdbc:mysql://fe1,fe2/db"))
        assertEmpty(problemIds("jdbc:mysql://fe1,fe2/db?port=9030"))
        assertEmpty(problemIds("jdbc:mysql://fe1,fe2/db?port=%39%30%33%30"))
        assertEquals(setOf("doris.port.missing"), problemIds("jdbc:mysql://fe1:/db"))
        assertEquals(setOf("doris.port.missing"), problemIds("jdbc:mysql://(host=fe1,port=)/db"))
        assertEquals(setOf("doris.port.mysql"), problemIds("jdbc:mysql://(host=fe1,port=3306)/db"))
        assertEquals(setOf("doris.port.missing"), problemIds("jdbc:mysql://fe1/db?dnsSrv=true"))
    }

    fun testMalformedAndMissingHostsRemainErrors() {
        val invalid = listOf(
            "jdbc:mysql://fe1:abc/db",
            "jdbc:mysql://fe1:70000/db",
            "jdbc:mysql://fe1:9030,/db",
            "jdbc:mysql://2001:db8::1:9030/db",
            "jdbc:mysql://(host=fe1,port=9030/db",
            "jdbc:mysql://fe1:9030//db",
            "jdbc:mysql://fe1:9030/db??x=1",
            "jdbc:mysql://fe1:9030/db?=value",
            "jdbc:mysql://fe1:9030/db?x=%ZZ",
            "jdbc:mysql://address=junk(host=fe1)trash(port=9030)/db",
            "jdbc:mysql://address=(host=fe1)(port=9030)junk/db",
        )
        for (url in invalid) assertTrue("$url -> ${problemIds(url)}", "doris.url.invalid" in problemIds(url))

        for (url in listOf("jdbc:mysql:///db", "jdbc:mysql://(port=9030)/db", "jdbc:mysql://address=(port=9030)/db")) {
            assertTrue("$url -> ${problemIds(url)}", "doris.host.missing" in problemIds(url))
        }
        assertEmpty(problemIds("jdbc:mysql://fe1:9030/db?x=what?ever"))
        assertEmpty(problemIds("jdbc:mysql://fe1:9030/db#x?y?z"))
    }

    fun testNonDorisDataSourceIsIgnored() {
        val source = source("jdbc:mysql://fe1/db")
        source.databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("mysql"))
        val problems = mutableListOf<DataSourceProblem>()
        validator.collectProblems(project, source, Consumer { problems.add(it) }, null)
        assertEmpty(problems)
    }

    private fun problemIds(url: String): Set<String> {
        val problems = mutableListOf<DataSourceProblem>()
        validator.collectProblems(project, source(url), Consumer { problems.add(it) }, null)
        return problems.mapNotNull { it.id?.toString() }.toSet()
    }

    private fun source(url: String) = LocalDataSource().apply {
        name = "Doris validator test"
        this.url = url
        databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
    }
}
