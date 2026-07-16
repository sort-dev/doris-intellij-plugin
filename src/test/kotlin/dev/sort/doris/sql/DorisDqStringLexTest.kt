package dev.sort.doris.sql

import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlTokens
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Pins the [DorisLexer] double-quote remap: `"…"` is ALWAYS a string literal in Doris (backticks
 * are the only identifier quoting; ANSI_QUOTES is a server no-op), but MysqlLexer lexes it as a
 * delimited identifier — the same token type backticks get. The remap swaps exactly the
 * double-quoted tokens to SQL_STRING_TOKEN and nothing else.
 *
 * Also pins the boundary facts the remap RELIES on (verified by probe against MysqlLexer):
 * multi-line content and backslash-escaped quotes lex within ONE token; unterminated quotes
 * become SQL_UNCLOSED_TOKEN and stay untouched.
 */
class DorisDqStringLexTest : BasePlatformTestCase() {

    private fun tokens(text: String): List<Pair<IElementType, String>> {
        val lx = DorisLexer()
        lx.start(text, 0, text.length, 0)
        val out = ArrayList<Pair<IElementType, String>>()
        while (lx.tokenType != null) {
            val t = text.substring(lx.tokenStart, lx.tokenEnd)
            if (t.isNotBlank()) out.add(lx.tokenType!! to t)
            lx.advance()
        }
        return out
    }

    private fun typeOf(text: String, token: String): IElementType =
        tokens(text).first { it.second == token }.first

    fun testDoubleQuotedLexesAsString() {
        assertEquals(SqlTokens.SQL_STRING_TOKEN, typeOf("SELECT \"abc\" FROM t", "\"abc\""))
    }

    fun testDoubleAndSingleQuotedGetTheSameTokenType() {
        assertEquals(
            typeOf("SELECT 'abc' FROM t", "'abc'"),
            typeOf("SELECT \"abc\" FROM t", "\"abc\""),
        )
    }

    fun testBacktickStaysDelimitedIdentifier() {
        assertEquals(SqlTokens.SQL_IDENT_DELIMITED, typeOf("SELECT `abc` FROM t", "`abc`"))
    }

    fun testMultilineAndEscapesStayOneStringToken() {
        val v = "\"l1\nl2 \\\" '2026-05-01' x\""
        assertEquals(SqlTokens.SQL_STRING_TOKEN, typeOf("SELECT $v FROM t", v))
    }

    fun testPropertyPairBecomesStringEqualsString() {
        val toks = tokens("SELECT * FROM QUERY('q' = \"select 1\")")
        assertEquals(SqlTokens.SQL_STRING_TOKEN, toks.first { it.second == "'q'" }.first)
        assertEquals(SqlTokens.SQL_STRING_TOKEN, toks.first { it.second == "\"select 1\"" }.first)
    }

    fun testUnterminatedDoubleQuoteUntouched() {
        val toks = tokens("SELECT \"oops FROM t")
        assertTrue(
            "unterminated DQ must stay an unclosed token, got: $toks",
            toks.any { it.first.toString().contains("UNCLOSED") && it.second.startsWith("\"oops") },
        )
    }
}
