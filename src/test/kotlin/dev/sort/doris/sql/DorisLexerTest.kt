package dev.sort.doris.sql

import com.intellij.sql.dialects.mysql.MysqlLexer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Token-stream fidelity: DorisLexer must be byte-identical to MysqlLexer except where it masks. */
class DorisLexerTest : BasePlatformTestCase() {

    private fun tokens(lexer: com.intellij.lexer.Lexer, text: String): List<String> {
        val out = ArrayList<String>()
        lexer.start(text)
        while (lexer.tokenType != null) {
            out.add("${lexer.tokenType} '${text.substring(lexer.tokenStart, lexer.tokenEnd)}'")
            lexer.advance()
        }
        return out
    }

    private fun parseTree(sql: String): String {
        val file = com.intellij.psi.PsiFileFactory.getInstance(project)
            .createFileFromText("t.sql", com.intellij.lang.Language.findLanguageByID("DorisSQL")!!, sql, false, true)
        return com.intellij.psi.impl.DebugUtil.psiToString(file!!, true)
    }

    fun testPureMysqlCastParses() {
        val tree = parseTree("select CAST(x AS CHAR) from t;")
        println("=== pure MySQL CAST ===")
        println(tree)
        assertFalse("CAST(x AS CHAR) must parse without errors", tree.contains("PsiErrorElement"))
    }

    fun testDorisTypedCastParses() {
        val tree = parseTree("select CAST(CAST(v AS JSON) AS STRING) from t;")
        println("=== Doris-typed CAST ===")
        println(tree)
        assertFalse("CAST(... AS STRING) must parse without errors", tree.contains("PsiErrorElement"))
    }

    fun testCountStarQueryClean() {
        val tree = parseTree(
            "select count(*) from acme_import.events\n" +
            "where event_at >= '2026-06-29'\n" +
            "  AND INSTR(CAST(CAST(event_value AS JSON) AS STRING), 'ephemeral') > 0\n" +
            "limit 1000;"
        )
        println("=== count(*) + INSTR/CAST query ===")
        println(tree)
        assertFalse("the count(*) query must parse without errors", tree.contains("PsiErrorElement"))
    }

    fun testTokensIdenticalWhenNoMaskApplies() {
        // JSON and CHAR are MySQL-valid cast targets -> nothing masked -> streams must be identical.
        val sql = "select count(*) from t where INSTR(CAST(CAST(v AS JSON) AS CHAR), 'x') > 0 limit 10;"
        val mysql = tokens(MysqlLexer(), sql)
        val doris = tokens(DorisLexer(), sql)
        assertEquals("DorisLexer must not alter tokens when no mask applies", mysql, doris)
    }

    fun testDorisCastTailMasked() {
        val sql = "select CAST(v AS STRING) from t;"
        val doris = tokens(DorisLexer(), sql)
        println(doris.joinToString("\n"))
        assertTrue("AS STRING must be masked as one comment",
            doris.any { it.startsWith("SQL_BLOCK_COMMENT") && it.contains("AS STRING") })
    }

    fun testTryCastGenericTailMasked() {
        val sql = "select TRY_CAST(x AS ARRAY<TEXT>) from t;"
        val doris = tokens(DorisLexer(), sql)
        println(doris.joinToString("\n"))
        assertTrue("AS ARRAY<TEXT> must be masked",
            doris.any { it.startsWith("SQL_BLOCK_COMMENT") && it.contains("ARRAY") })
    }
}
