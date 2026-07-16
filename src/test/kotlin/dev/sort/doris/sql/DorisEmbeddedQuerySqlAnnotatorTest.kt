package dev.sort.doris.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Pins [DorisEmbeddedQuerySqlAnnotator]: the string payload of `query(..., 'query' = '<sql>')`
 * gets basic lexical SQL coloring (keywords/identifiers/numbers; nested literals keep string
 * color) — scoped to exactly that property of exactly that function.
 */
class DorisEmbeddedQuerySqlAnnotatorTest : BasePlatformTestCase() {

    private var counter = 0

    private fun highlight(sql: String): List<HighlightInfo> {
        val psi = myFixture.configureByText("eq${counter++}.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DorisSqlDialect.INSTANCE)
        return myFixture.doHighlighting()
    }

    private fun paintAt(all: List<HighlightInfo>, sql: String, word: String, from: Int = 0): List<TextAttributesKey> {
        val start = sql.indexOf(word, from).also { check(it >= 0) { "'$word' not in sql" } }
        return all.filter { it.startOffset == start && it.endOffset == start + word.length }
            .mapNotNull { it.forcedTextAttributesKey }
    }

    fun testPayloadKeywordsIdentifiersAndNumbersPainted() {
        val sql = "SELECT * FROM QUERY('catalog' = 'c1', " +
            "'query' = \"select publisher_id, sum(imps) from stats.imps where d >= '2026-05-01' limit 10\");"
        val all = highlight(sql)
        val q = sql.indexOf("\"select")
        assertTrue(paintAt(all, sql, "select", q).contains(DefaultLanguageHighlighterColors.KEYWORD))
        assertTrue(paintAt(all, sql, "where", q).contains(DefaultLanguageHighlighterColors.KEYWORD))
        assertTrue(paintAt(all, sql, "publisher_id", q).contains(DefaultLanguageHighlighterColors.IDENTIFIER))
        assertTrue(paintAt(all, sql, "10", q).contains(DefaultLanguageHighlighterColors.NUMBER))
        // the nested '2026-05-01' literal keeps string color — no paint over it
        assertTrue(paintAt(all, sql, "2026-05-01", q).isEmpty())
    }

    fun testSingleQuotedPayloadPaintedToo() {
        val sql = "SELECT * FROM query('catalog' = 'c1', 'query' = 'select 1 from t');"
        val all = highlight(sql)
        val q = sql.indexOf("'select")
        assertTrue(paintAt(all, sql, "select", q).contains(DefaultLanguageHighlighterColors.KEYWORD))
    }

    fun testOtherPropertiesAndFunctionsUntouched() {
        val sql = "SELECT * FROM QUERY('catalog' = 'select from where', 'query' = 'select 1'), " +
            "s3('query' = 'select also here');"
        val all = highlight(sql)
        // 'catalog' value: no paint even though it contains keyword words
        val cat = sql.indexOf("'select from where'")
        assertTrue(paintAt(all, sql, "select", cat).isEmpty())
        // same-named property under a DIFFERENT function: no paint
        val s3 = sql.indexOf("'select also here'")
        assertTrue(paintAt(all, sql, "select", s3).isEmpty())
    }
}
