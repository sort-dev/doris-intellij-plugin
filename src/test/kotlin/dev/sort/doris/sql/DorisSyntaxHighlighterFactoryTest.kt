package dev.sort.doris.sql

import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.psi.SqlTokens
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.lexAll

class DorisSyntaxHighlighterFactoryTest : BasePlatformTestCase() {
    fun testKeywordOverlayUsesSqlColorsAndPreservesOtherTokens() {
        val sql = "distributed DISTRIBUTED plain_column `distributed` 'distributed' \"distributed\" 42 /* DISTRIBUTED */"
        val base = object : SqlSyntaxHighlighterFactory.Base(DorisSqlDialect.INSTANCE) {}
            .getSyntaxHighlighter(project, null)
        val overlay = DorisSyntaxHighlighterFactory().getSyntaxHighlighter(project, null)
        val repeated = DorisSyntaxHighlighterFactory().getSyntaxHighlighter(project, null)
        val original = base.highlightingLexer.lexAll(sql)
        val actual = overlay.highlightingLexer.lexAll(sql)
        assertEquals(original.size, actual.size)
        assertEquals(2, original.zip(actual).count { (before, after) -> before.type != after.type })
        assertEquals(actual, repeated.highlightingLexer.lexAll(sql))
        for ((before, after) in original.zip(actual)) {
            assertEquals(before.start, after.start)
            assertEquals(before.end, after.end)
            assertEquals(before.state, after.state)
            if (before.type == SqlTokens.SQL_IDENT && sql.substring(before.start, before.end).uppercase() in DORIS_KEYWORDS) {
                assertNotSame(before.type, after.type)
                assertTrue(base.getTokenHighlights(SqlTokens.SQL_KEYWORD_TOKEN)
                    .contentEquals(overlay.getTokenHighlights(after.type)))
            } else {
                assertSame(before.type, after.type)
                assertTrue(base.getTokenHighlights(before.type).contentEquals(overlay.getTokenHighlights(after.type)))
            }
        }
    }
}
