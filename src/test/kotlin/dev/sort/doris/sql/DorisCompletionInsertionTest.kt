package dev.sort.doris.sql

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.RunAll
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.intellij.completion.SqlLookupIcons

class DorisCompletionInsertionTest : BasePlatformTestCase() {
    private lateinit var catalog: DorisFunctionCatalogFixture

    override fun setUp() {
        super.setUp()
        catalog = DorisFunctionCatalogFixture(testRootDisposable)
    }

    override fun tearDown() {
        RunAll(
            { catalog.errors.awaitScopes() },
            { catalog.errors.assertNone() },
            { setSqlDialectMapping(project, null, null) },
            { super.tearDown() },
        ).run()
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

    fun testHarvestOffersGrammarFormsAndServerOnlyFunctions() {
        configure("SELECT <caret>")
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            val id = recording.console.dataSource.uniqueId
            catalog.harvest(recording.console.dataSource, listOf("ABS", "SERVER_ONLY_FN"))
            assertEquals(id, dorisDataSourceResolver.resolve(myFixture.file).dataSource?.uniqueId)
            val items = myFixture.completeBasic().orEmpty()
            val names = items.filter { it.`object` is FunctionPresentation }.map { it.lookupString }
            assertTrue("CAST must be offered despite being absent from the harvest: $names", "cast" in names)
            assertTrue("Server-only functions must be offered: $names", "server_only_fn" in names)
        }
    }

    fun testForeignConsoleHarvestIsNotUsed() {
        configure("SELECT <caret>")
        RecordingConsole(project, myFixture.file.virtualFile, "mysql").use { recording ->
            catalog.harvest(recording.console.dataSource, listOf("ABS", "FOREIGN_ONLY_FN"))
            assertNull(dorisDataSourceResolver.resolve(myFixture.file).dataSource)
            val names = myFixture.completeBasic().orEmpty()
                .filter { it.`object` is FunctionPresentation }.map { it.lookupString }
            assertTrue("A foreign harvest must not filter the Doris baseline: $names", "acos" in names)
            assertFalse("A foreign harvest must not add server functions: $names", "foreign_only_fn" in names)
        }
    }

    fun testNonQueryableTableFunctionsAreNotOffered() {
        for (prefix in listOf("bin", "group_com", "http_str", "cdc_str")) {
            configure("SELECT * FROM $prefix<caret>")
            val entries = myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
            assertFalse(entries.toString(), entries.any { it.name.lowercase() in setOf("binlog", "group_commit", "http_stream", "cdc_stream") })
        }
    }

    fun testWindowFunctionUsesSharedWindowIconAndDorisLabel() {
        configure("SELECT ra<caret>")
        val item = myFixture.completeBasic().orEmpty().single { it.lookupString == "rank" && it.`object` is FunctionPresentation }
        val presentation = LookupElementPresentation.renderElement(item)
        assertEquals(SqlLookupIcons.WindowFunction, presentation.icon)
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
