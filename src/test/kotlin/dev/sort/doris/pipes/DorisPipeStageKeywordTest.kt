package dev.sort.doris.pipes

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.sql.DorisSqlDialect

class DorisPipeStageKeywordTest : BasePlatformTestCase() {
    private val settings get() = project.service<DorisPipesSettings>()
    private var counter = 0

    override fun setUp() {
        super.setUp()
        System.clearProperty(DorisPipes.PROPERTY)
        settings.enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            settings.enabled = false
            System.clearProperty(DorisPipes.PROPERTY)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    fun testStageHeadsReceiveKeywordAttributes() {
        val sql = "FROM t |> EXTEND 1 AS x |> AGGREGATE count(*) AS n |> OFFSET 1"
        val highlights = highlight(sql)
        assertTrue(isForcedKeyword(highlights, sql, "EXTEND"))
        assertFalse(isForcedKeyword(highlights, sql, "AGGREGATE"))
        assertFalse(isForcedKeyword(highlights, sql, "OFFSET"))
    }

    fun testContextAndNativeOperatorGatePreventGlobalColoring() {
        val sql = "SELECT extend, aggregate FROM extend AS e; " +
            "SELECT '|> EXTEND', \"|> AGGREGATE\", `EXTEND`; " +
            "-- |> EXTEND\n/* |> AGGREGATE */ SELECT 1 | > EXTEND, 1 |/*gap*/> EXTEND; " +
            "FROM t ||> EXTEND 1 AS x; FROM t |> EXTENDé; FROM t |> EXTEND\$foo"
        val highlights = highlight(sql)
        for (word in listOf("extend", "aggregate", "EXTEND", "AGGREGATE")) {
            var from = 0
            while (true) {
                val index = sql.indexOf(word, from)
                if (index < 0) break
                assertFalse("unexpected PIPE keyword at $index in $sql", isForcedKeyword(highlights, index, word.length))
                from = index + word.length
            }
        }
    }

    fun testCommentBetweenOperatorAndLowercaseStageIsSupported() {
        val sql = "SELECT '\uD83D\uDE00'; FROM t |> /* stage */ extend 1 AS x"
        assertTrue(isForcedKeyword(highlight(sql), sql, "extend"))
    }

    fun testDisabledProjectDoesNotForceStageColoring() {
        settings.enabled = false
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val sql = "FROM t |> EXTEND 1 AS x"
        assertFalse(isForcedKeyword(highlight(sql), sql, "EXTEND"))
    }

    fun testStageCompletionStopsAfterACompleteHead() {
        assertTrue("EXTEND" in pipeStageItems("FROM t |> <caret>"))
        assertTrue("FULL OUTER JOIN" in pipeStageItems("FROM t |> FULL O<caret>"))
        assertEmpty(pipeStageItems("FROM t |> EXTEND <caret>"))
        assertEmpty(pipeStageItems("FROM t |> ORDER BY <caret>"))
        assertEmpty(pipeStageItems("SELECT <caret>1"))
        assertEmpty(pipeStageItems("FROM t ||> <caret>"))
        assertEmpty(pipeStageItems("SELECT '|> <caret>'"))
        assertEmpty(pipeStageItems("SELECT 1 /* |> <caret> */"))
    }

    private fun highlight(sql: String): List<HighlightInfo> {
        val file = myFixture.configureByText("pipe-keywords-${counter++}.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(file.virtualFile, DorisSqlDialect.INSTANCE)
        return myFixture.doHighlighting()
    }

    private fun pipeStageItems(sql: String): List<String> {
        val mappings = SqlDialectMappings.getInstance(project)
        mappings.setMapping(null, DorisSqlDialect.INSTANCE)
        try {
            myFixture.configureByText("pipe-completion-${counter++}.sql", sql)
            return myFixture.completeBasic().orEmpty().mapNotNull { item ->
                val presentation = LookupElementPresentation()
                item.renderElement(presentation)
                item.lookupString.takeIf { presentation.typeText == "pipe stage" }
            }
        } finally {
            mappings.setMapping(null, null)
        }
    }

    private fun isForcedKeyword(highlights: List<HighlightInfo>, sql: String, word: String): Boolean {
        val start = sql.indexOf(word).also { check(it >= 0) }
        return isForcedKeyword(highlights, start, word.length)
    }

    private fun isForcedKeyword(highlights: List<HighlightInfo>, start: Int, length: Int): Boolean =
        highlights.any {
            it.startOffset == start && it.endOffset == start + length &&
                it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.KEYWORD
        }
}
