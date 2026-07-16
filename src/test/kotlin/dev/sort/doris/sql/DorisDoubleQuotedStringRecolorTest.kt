package dev.sort.doris.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Pins [DorisDoubleQuotedStringRecolorAnnotator] (dogfood 2026-07-16): `"…"` is always a string
 * in Doris, but the MySQL substrate colored it as an ANSI quoted identifier — errors were already
 * suppressed, so the editor showed a confidently-wrong identifier-purple string. The annotator
 * paints STRING attributes over exactly the double-quoted token; backtick identifiers and real
 * comments stay untouched.
 */
class DorisDoubleQuotedStringRecolorTest : BasePlatformTestCase() {

    private var counter = 0

    private fun highlight(sql: String): List<HighlightInfo> {
        val psi = myFixture.configureByText("q${counter++}.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DorisSqlDialect.INSTANCE)
        return myFixture.doHighlighting()
    }

    private fun stringRecolorsOver(all: List<HighlightInfo>, sql: String, token: String): Boolean {
        val start = sql.indexOf(token).also { check(it >= 0) { "'$token' not in fixture sql" } }
        return all.any {
            it.startOffset == start && it.endOffset == start + token.length &&
                it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.STRING
        }
    }

    fun testDoubleQuotedTvfPropertyValueRecoloredAsString() {
        val value = "\"select d, sum(v) as c\n    from stats.imps\n    where d >= '2026-05-01'\n    group by d\""
        val sql = "SELECT * FROM QUERY('catalog' = 'ch_cat', 'query' = $value);"
        assertTrue(
            "multi-line double-quoted property value must be painted as a string",
            stringRecolorsOver(highlight(sql), sql, value),
        )
    }

    fun testDoubleQuotedTokenInSelectListRecoloredAsString() {
        val sql = "SELECT \"plain text\" FROM t;"
        assertTrue(
            "double-quoted select-list token must be painted as a string",
            stringRecolorsOver(highlight(sql), sql, "\"plain text\""),
        )
    }

    fun testBacktickIdentifierNotStringPainted() {
        val sql = "SELECT 1 AS `my_alias` FROM t;"
        val all = highlight(sql)
        val start = sql.indexOf("`my_alias`")
        assertTrue(
            "backtick identifiers must not get the string paint",
            all.none {
                it.startOffset == start && it.endOffset == start + "`my_alias`".length &&
                    it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.STRING
            },
        )
    }

    fun testMaskedSpanPseudoCommentNotRepainted() {
        // A DorisLexer masked span is a pseudo-comment; if its text begins and ends with a quote
        // the recolor must NOT flatten the whole span into one string (the masked-span annotator
        // owns those ranges). EXCEPT masking produces such a span with a quoted column list.
        val sql = "SELECT * EXCEPT(secret_col) FROM (SELECT 1 AS secret_col, 2 AS b) t;"
        val all = highlight(sql)
        val start = sql.indexOf("EXCEPT")
        assertTrue(
            "masked EXCEPT span must keep its keyword recolor, not become a string",
            all.none {
                it.startOffset <= start && it.endOffset > start &&
                    it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.STRING
            },
        )
    }
}
