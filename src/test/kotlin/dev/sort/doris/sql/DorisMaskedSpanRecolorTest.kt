package dev.sort.doris.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.psi.SqlTokens
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.lexAll
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping

/**
 * Pins [DorisMaskedSpanRecolorAnnotator] (dogfood 2026-07-08 P3: CAST targets colored like
 * comments). The DorisLexer masks Doris-only cast tails (`AS LARGEINT`, `AS ARRAY<...>`) as one
 * SQL_BLOCK_COMMENT token, and — because the platform SqlSyntaxHighlighter lexes with the
 * ParserDefinition's lexer — the editor colored the whole span as a comment. The annotator lays
 * keyword/identifier/literal TextAttributes back over the masked ranges. Pinned both ways: the
 * masked words get their recolor annotation, and genuine comments / MySQL-valid cast targets get
 * none.
 */
class DorisMaskedSpanRecolorTest : BasePlatformTestCase() {

    private var counter = 0

    private fun highlight(sql: String): List<HighlightInfo> {
        val psi = myFixture.configureByText("r${counter++}.sql", sql)
        setSqlDialectMapping(project, psi.virtualFile, DorisSqlDialect.INSTANCE)
        return myFixture.doHighlighting()
    }

    /** Recolor annotations (forced text attributes) covering exactly [word] at its first index from [from]. */
    private fun recolorsAt(all: List<HighlightInfo>, sql: String, word: String, from: Int = 0): List<TextAttributesKey> {
        val start = sql.indexOf(word, from)
        assertTrue("'$word' not found in fixture sql", start >= 0)
        return all.filter { it.startOffset == start && it.endOffset == start + word.length }
            .mapNotNull { it.forcedTextAttributesKey }
    }

    private fun sqlColor(type: IElementType): TextAttributesKey =
        SyntaxHighlighterFactory.getSyntaxHighlighter(DorisSqlDialect.INSTANCE, project, myFixture.file.virtualFile)
            .getTokenHighlights(type).lastOrNull() ?: HighlighterColors.TEXT

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

    fun testCastTargetTypeRecoloredAsKeyword() {
        val sql = "SELECT CAST(v AS LARGEINT) FROM t;"
        val all = highlight(sql)
        assertTrue(
            "masked cast target LARGEINT must be recolored as a keyword",
            recolorsAt(all, sql, "LARGEINT").contains(sqlColor(SqlTokens.SQL_KEYWORD_TOKEN)),
        )
        // the masked AS keyword too (space-anchored: 'CAST' contains the substring 'AS')
        assertTrue(
            "masked AS must be recolored as a keyword",
            recolorsAt(all, sql, "AS", sql.indexOf(" AS ") + 1).contains(sqlColor(SqlTokens.SQL_KEYWORD_TOKEN)),
        )
    }

    fun testGenericCastTargetWordsRecolored() {
        val sql = "SELECT TRY_CAST(x AS MAP<STRING, INT>) FROM t;"
        val all = highlight(sql)
        assertTrue(
            "MAP recolored as keyword",
            recolorsAt(all, sql, "MAP").contains(sqlColor(SqlTokens.SQL_KEYWORD_TOKEN)),
        )
        assertTrue(
            "STRING recolored as keyword",
            recolorsAt(all, sql, "STRING").contains(sqlColor(SqlTokens.SQL_KEYWORD_TOKEN)),
        )
    }

    fun testExceptMaskRecoloredKeywordAndIdentifiers() {
        val sql = "SELECT * EXCEPT(secret_col) FROM (SELECT 1 AS secret_col, 2 AS b) t;"
        val all = highlight(sql)
        assertTrue(
            "masked EXCEPT recolored as keyword",
            recolorsAt(all, sql, "EXCEPT").contains(sqlColor(SqlTokens.SQL_KEYWORD_TOKEN)),
        )
        assertTrue(
            "masked excluded column recolored as identifier",
            recolorsAt(all, sql, "secret_col").contains(sqlColor(SqlTokens.SQL_IDENT)),
        )
    }

    fun testMysqlValidCastTargetNotMasked() {
        // CHAR is a MySQL-valid target: nothing is masked (no PsiComment over the cast tail), so
        // the annotator — which only visits PsiComments — provably leaves this statement alone.
        // (Cannot assert on forced attributes here: the platform's own semantic highlighting also
        // forces attributes on keyword/type tokens.)
        val sql = "SELECT CAST(v AS CHAR) FROM t;"
        highlight(sql)
        val comments = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(myFixture.file, com.intellij.psi.PsiComment::class.java)
        assertTrue("no masked span for a MySQL-valid cast target, got: $comments", comments.isEmpty())
    }

    fun testRealCommentsStayComments() {
        val sql = "SELECT 1; /* AS LARGEINT */ -- AS STRING"
        val all = highlight(sql)
        assertTrue(
            "genuine comments must not be recolored",
            all.none { it.forcedTextAttributesKey != null && it.startOffset >= sql.indexOf("/*") },
        )
    }
}
