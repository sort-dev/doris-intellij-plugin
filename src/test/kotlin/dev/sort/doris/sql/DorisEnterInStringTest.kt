package dev.sort.doris.sql

import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Enter inside a Doris string literal must insert a PLAIN NEWLINE — Doris strings are multi-line
 * (dogfood: multi-line TVF property values). Without [DorisQuoteHandler] the platform's
 * EnterInStringLiteralHandler (via base-SQL's Concat quote handler) split the literal into a
 * `'…' ||  '…'` concatenation on every Enter, making those values un-typeable.
 */
class DorisEnterInStringTest : BasePlatformTestCase() {

    private fun typeEnterAt(sql: String): String {
        val psi = myFixture.configureByText("e.sql", sql)
        SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DorisSqlDialect.INSTANCE)
        myFixture.type('\n')
        return myFixture.editor.document.text
    }

    fun testEnterInSingleQuotedStringInsertsPlainNewline() {
        val text = typeEnterAt("SELECT * FROM QUERY('catalog' = 'c', 'query' = 'select a<caret> from x');")
        assertFalse("string must not be split into a concatenation: $text", text.contains("||"))
        assertTrue("expected a plain newline inside the literal: $text", text.contains("'select a\n"))
    }

    fun testEnterInDoubleQuotedStringInsertsPlainNewline() {
        val text = typeEnterAt("SELECT * FROM QUERY('catalog' = 'c', 'query' = \"select a<caret> from x\");")
        assertFalse("string must not be split into a concatenation: $text", text.contains("||"))
        assertTrue("expected a plain newline inside the literal: $text", text.contains("\"select a\n"))
    }

    fun testEnterOutsideStringsStillNormal() {
        val text = typeEnterAt("SELECT 1;<caret>SELECT 2;")
        assertTrue(text.contains("SELECT 1;\nSELECT 2;"))
    }
}
