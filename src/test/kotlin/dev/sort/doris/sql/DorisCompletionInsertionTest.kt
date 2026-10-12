package dev.sort.doris.sql

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.sort.doris.setSqlDialectMapping
import icons.DatabaseIcons

class DorisCompletionInsertionTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            DorisBuiltinCatalog.clear()
            setSqlDialectMapping(project, null, null)
        } finally {
            super.tearDown()
        }
    }

    private fun configure(sql: String) {
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("completion.sql", sql)
    }

    private fun accept(sql: String, name: String, completionChar: Char = '\n') {
        configure(sql)
        val items = myFixture.completeBasic().orEmpty()
        val item = items.single { it.lookupString == name && it.`object` is FunctionPresentation }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(completionChar)
    }

    fun testFunctionInsertsCallWithCaretInArguments() {
        accept("SELECT ab<caret>", "abs")
        myFixture.checkResult("SELECT abs(<caret>)")
    }

    fun testExistingParenthesisIsReused() {
        accept("SELECT ab<caret>(1)", "abs")
        myFixture.checkResult("SELECT abs(<caret>1)")
    }

    fun testArgumentlessCallPlacesCaretAfterParentheses() {
        accept("SELECT pi<caret>", "pi")
        myFixture.checkResult("SELECT pi()<caret>")
    }

    fun testBareGrammarFormDoesNotInsertParentheses() {
        accept("SELECT current_time<caret>", "current_timestamp")
        myFixture.checkResult("SELECT current_timestamp<caret>")
    }

    fun testSpecialGrammarFormDoesNotInsertParentheses() {
        accept("SELECT ca<caret>", "cast")
        myFixture.checkResult("SELECT cast<caret>")
    }

    fun testTypedParenthesisAcceptsBareFormAsCall() {
        accept("SELECT current_time<caret>", "current_timestamp", '(')
        myFixture.checkResult("SELECT current_timestamp(<caret>)")
    }

    fun testIcebergPropertyInsertionUsesDoubleQuotes() {
        configure("SELECT * FROM iceberg_meta(qu<caret>)")
        val item = myFixture.completeBasic().orEmpty().single { it.lookupString == "query_type" }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup('\n')
        myFixture.checkResult("SELECT * FROM iceberg_meta(\"query_type\"<caret>)")
    }

    fun testIcebergQueryTypesAreRetained() {
        configure("SELECT * FROM iceberg_meta('query_type'='<caret>')")
        val names = myFixture.completeBasic().orEmpty().map { it.lookupString }.toSet()
        assertTrue(names.toString(), names.containsAll(listOf("snapshots", "manifests", "all_manifests", "files",
            "data_files", "delete_files", "partitions", "refs", "history", "metadata_log_entries")))
    }

    fun testHarvestWithoutIcebergMetaStillOffersRetainedTvf() {
        configure("SELECT * FROM <caret>")
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            val id = recording.console.dataSource.uniqueId
            DorisBuiltinCatalog.record(id, setOf("ABS"))
            assertEquals(id, DorisBuiltinCatalog.dataSourceIdFor(myFixture.file))
            val items = myFixture.completeBasic().orEmpty()
            val iceberg = items.singleOrNull { it.lookupString == "iceberg_meta" && it.`object` is FunctionPresentation }
            assertNotNull("iceberg_meta must remain offered when the console harvest omits it", iceberg)
            assertEquals("Doris table function", LookupElementPresentation.renderElement(iceberg!!).typeText)
            assertFalse("Registry functions absent from the harvest must still be filtered",
                items.any { it.lookupString == "acos" && it.`object` is FunctionPresentation })
        }
    }

    fun testNonQueryableTableFunctionsAreNotOffered() {
        for (prefix in listOf("bin", "group_com", "http_str", "cdc_str")) {
            configure("SELECT * FROM $prefix<caret>")
            val entries = myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
            assertFalse(entries.toString(), entries.any { it.name.lowercase() in setOf("binlog", "group_commit", "http_stream", "cdc_stream") })
        }
    }

    fun testWindowFunctionUsesAggregateIconAndDorisLabel() {
        configure("SELECT ra<caret>")
        val item = myFixture.completeBasic().orEmpty().single { it.lookupString == "rank" && it.`object` is FunctionPresentation }
        val presentation = LookupElementPresentation.renderElement(item)
        assertEquals(DatabaseIcons.Aggregate, presentation.icon)
        assertEquals("Doris window function", presentation.typeText)
    }

    fun testVariadicSignatureUsesThreeDots() {
        configure("SELECT co<caret>")
        val item = myFixture.completeBasic().orEmpty().single { it.lookupString == "concat" && it.`object` is FunctionPresentation }
        val presentation = LookupElementPresentation.renderElement(item)
        assertTrue(presentation.tailText, presentation.tailText.orEmpty().contains("..."))
        assertFalse(presentation.tailText.orEmpty().contains("…"))
    }
}
