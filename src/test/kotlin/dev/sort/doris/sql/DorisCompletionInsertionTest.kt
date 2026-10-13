package dev.sort.doris.sql

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.application.options.CodeStyle
import com.intellij.database.util.Case
import com.intellij.sql.formatter.settings.SqlCodeStyleSettings
import com.intellij.sql.formatter.settings.SqlCodeStyles
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
        accept("SELECT ab<caret>", "ABS")
        myFixture.checkResult("SELECT ABS(<caret>)")
    }

    fun testExistingParenthesisIsReused() {
        accept("SELECT ab<caret>(1)", "ABS")
        myFixture.checkResult("SELECT ABS(<caret>1)")
    }

    fun testArgumentlessCallPlacesCaretAfterParentheses() {
        accept("SELECT pi<caret>", "PI")
        myFixture.checkResult("SELECT PI()<caret>")
    }

    fun testBareGrammarFormDoesNotInsertParentheses() {
        accept("SELECT current_time<caret>", "CURRENT_TIMESTAMP")
        myFixture.checkResult("SELECT CURRENT_TIMESTAMP<caret>")
    }

    fun testSpecialGrammarFormDoesNotInsertParentheses() {
        accept("SELECT ca<caret>", "CAST")
        myFixture.checkResult("SELECT CAST<caret>")
    }

    fun testTypedParenthesisAcceptsBareFormAsCall() {
        accept("SELECT current_time<caret>", "CURRENT_TIMESTAMP", '(')
        myFixture.checkResult("SELECT CURRENT_TIMESTAMP(<caret>)")
    }

    fun testAggregateInsertsUppercaseCall() {
        accept("SELECT su<caret>", "SUM")
        myFixture.checkResult("SELECT SUM(<caret>)")
    }

    fun testWindowFunctionInsertsUppercaseCall() {
        accept("SELECT ra<caret>", "RANK")
        myFixture.checkResult("SELECT RANK(<caret>)")
    }

    fun testTableFunctionInsertsUppercaseCall() {
        accept("SELECT * FROM ta<caret>", "TASKS")
        myFixture.checkResult("SELECT * FROM TASKS(<caret>)")
    }

    fun testKeywordInsertsUppercaseText() {
        configure("sel<caret>")
        val items = myFixture.completeBasic()
        if (items != null) {
            myFixture.lookup.currentItem = items.single { it.lookupString.equals("SELECT", ignoreCase = true) }
            myFixture.finishLookup('\n')
        }
        assertEquals("SELECT", myFixture.editor.document.text.trim())
    }

    fun testDelegatedNativeKeywordUsesUppercaseForAMixedCasePrefix() {
        acceptKeyword("SELECT 1; sEl<caret>", "SELECT")
        myFixture.checkResult("SELECT 1; SELECT <caret>")
    }

    fun testDelegatedNativeMultiwordKeywordKeepsItsInsertion() {
        acceptKeyword("SELECT id FROM t gro<caret>", "GROUP BY")
        myFixture.checkResult("SELECT id FROM t GROUP BY <caret>")
    }

    fun testExplicitSqlKeywordCaseWinsOverTheProfile() {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCustomSettings(SqlCodeStyleSettings::class.java).KEYWORD_CASE = SqlCodeStyleSettings.TO_LOWER
            acceptKeyword("SELECT 1; SEL<caret>", "select")
            assertEquals(Case.LOWER, SqlCodeStyles.getSqlSettings(myFixture.file).keywordCase)
            myFixture.checkResult("SELECT 1; select <caret>")
        }
    }

    private fun acceptKeyword(sql: String, keyword: String) {
        configure(sql)
        val items = myFixture.completeBasic().orEmpty()
        val item = items.single { it.lookupString == keyword && it.`object` !is FunctionPresentation }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup('\n')
    }

    fun testHarvestOffersGrammarFormsAndServerOnlyFunctions() {
        configure("SELECT <caret>")
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            val id = recording.console.dataSource.uniqueId
            catalog.harvest(recording.console.dataSource, listOf("ABS", "SERVER_ONLY_FN"))
            assertEquals(id, dorisDataSourceResolver.resolve(myFixture.file).dataSource?.uniqueId)
            val items = myFixture.completeBasic().orEmpty()
            val names = items.filter { it.`object` is FunctionPresentation }.map { it.lookupString }
            assertTrue("CAST must be offered despite being absent from the harvest: $names", "CAST" in names)
            assertTrue("Server-only functions must be offered: $names", "SERVER_ONLY_FN" in names)
        }
    }

    fun testForeignConsoleHarvestIsNotUsed() {
        configure("SELECT <caret>")
        RecordingConsole(project, myFixture.file.virtualFile, "mysql").use { recording ->
            catalog.harvest(recording.console.dataSource, listOf("ABS", "FOREIGN_ONLY_FN"))
            assertNull(dorisDataSourceResolver.resolve(myFixture.file).dataSource)
            val names = myFixture.completeBasic().orEmpty()
                .filter { it.`object` is FunctionPresentation }.map { it.lookupString }
            assertTrue("A foreign harvest must not filter the Doris baseline: $names", "ACOS" in names)
            assertFalse("A foreign harvest must not add server functions: $names", "FOREIGN_ONLY_FN" in names)
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
        val item = myFixture.completeBasic().orEmpty().single { it.lookupString == "RANK" && it.`object` is FunctionPresentation }
        val presentation = LookupElementPresentation.renderElement(item)
        assertEquals(SqlLookupIcons.WindowFunction, presentation.icon)
        assertEquals("Doris window function", presentation.typeText)
    }

    fun testVariadicSignatureUsesThreeDots() {
        configure("SELECT co<caret>")
        val item = myFixture.completeBasic().orEmpty().single { it.lookupString == "CONCAT" && it.`object` is FunctionPresentation }
        val presentation = LookupElementPresentation.renderElement(item)
        assertTrue(presentation.tailText, presentation.tailText.orEmpty().contains("..."))
        assertFalse(presentation.tailText.orEmpty().contains("…"))
    }
}
