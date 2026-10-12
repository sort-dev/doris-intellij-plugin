package dev.sort.doris.sql

import com.intellij.psi.TokenType
import com.intellij.sql.dialects.mysql.MysqlLexer
import com.intellij.sql.psi.SqlTokens
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.assertRestartsMatchFullLex
import dev.brikk.house.sql.intellij.testing.lexAll

/** Token-stream fidelity: DorisLexer must be byte-identical to MysqlLexer except where it masks. */
class DorisLexerTest : BasePlatformTestCase() {

    private fun tokens(lexer: com.intellij.lexer.Lexer, text: String, startOffset: Int = 0): List<String> {
        return lexer.lexAll(text, startOffset).map { "${it.type} '${text.substring(it.start, it.end)}'" }
    }

    private fun parseTree(sql: String): String {
        val file = com.intellij.psi.PsiFileFactory.getInstance(project)
            .createFileFromText("t.sql", DorisSqlDialect.INSTANCE, sql, false, true)
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

    fun testInsertLagSingleLine() {
        val tree = parseTree(
            "INSERT OVERWRITE TABLE t PARTITION(*)\n" +
            "WITH sourced AS (SELECT * FROM x WHERE a >= '2026-01-01'),\n" +
            "withPrevEvent AS (\n" +
            "    SELECT *, LAG(earliest_event_at) OVER (PARTITION BY k) AS prev FROM sourced\n" +
            ")\n" +
            "SELECT * FROM withPrevEvent;"
        )
        println("=== insert + single-line LAG ===")
        println(tree)
    }

    fun testInsertLagMultiLine() {
        val tree = parseTree(
            "INSERT OVERWRITE TABLE t PARTITION(*)\n" +
            "WITH sourced AS (SELECT * FROM x WHERE a >= '2026-01-01'),\n" +
            "withPrevEvent AS (\n" +
            "    SELECT *,\n" +
            "        LAG(earliest_event_at) OVER (PARTITION BY k) AS prev\n" +
            "    FROM sourced\n" +
            ")\n" +
            "SELECT * FROM withPrevEvent;"
        )
        println("=== insert + multi-line LAG ===")
        println(tree)
    }

    fun testRawGrammarProbes() {
        // Inside INSERT the statement bypasses our dispatch — pure grammar behavior.
        val probes = mapOf(
            "IF multi-line" to
                "INSERT INTO t\nSELECT a,\n    IF(b IS NULL, 'x', 'y') AS c\nFROM s;",
            "REGEXP function form" to
                "INSERT INTO t\nSELECT REGEXP(ua, '(?i)bot') AS is_bot FROM s;"
        )
        for ((name, sql) in probes) {
            val tree = parseTree(sql)
            val errs = Regex("PsiErrorElement").findAll(tree).count()
            println("PROBE-RESULT $name -> $errs errors")
            assertEquals("$name must parse cleanly", 0, errs)
        }
    }

    fun testTopLevelMultiLineLagGetsRealStatement() {
        // With the builtin-function map fixed, window functions parse natively — no bounded wrapper.
        val tree = parseTree(
            "WITH s AS (SELECT 1 AS ts, 2 AS k)\n" +
            "SELECT *,\n" +
            "    LAG(ts) OVER (PARTITION BY k ORDER BY ts) AS prev FROM s;"
        )
        println(tree)
        assertFalse("no parse errors", tree.contains("PsiErrorElement"))
        assertTrue("must be a real select statement, not a generic bounded wrapper",
            tree.contains("SQL_SELECT_STATEMENT"))
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

    fun testTemporaryPartitionModifierMasked() {
        val sql = "INSERT INTO t TEMPORARY PARTITION(p1) SELECT * FROM s;"
        val doris = tokens(DorisLexer(), sql)
        assertTrue("TEMPORARY must be masked before a PARTITION selector",
            doris.any { it.startsWith("SQL_BLOCK_COMMENT") && it.contains("TEMPORARY") })
        assertTrue("the PARTITION selector must remain visible to MySQL's grammar",
            doris.any { it.contains("'PARTITION'") })
    }

    fun testTemporaryOutsidePartitionSelectorIsUnchanged() {
        val sql = "ALTER TABLE t ADD TEMPORARY PARTITION p1 VALUES LESS THAN ('2024-06-01');"
        assertEquals(tokens(MysqlLexer(), sql), tokens(DorisLexer(), sql))
    }

    fun testTemporaryPartitionMaskMatchesAtStatementBoundaryRestarts() {
        val sql = "SELECT 0;\nINSERT INTO t TEMPORARY PARTITION(p1)\nSELECT * FROM s;"
        assertRestartsMatchFullLex(::DorisLexer, sql)
        val doris = tokens(DorisLexer(), sql)
        assertTrue("TEMPORARY must remain masked after a restart at a statement boundary",
            doris.any { it.startsWith("SQL_BLOCK_COMMENT") && it.contains("TEMPORARY") })
    }

    fun testTemporaryPartitionTokensMatchAfterRangeDdlAndLexerRestarts() {
        val sql = """
            ALTER TABLE t ADD TEMPORARY PARTITION p1 VALUES [('2024-05-02'), ('2024-05-03'));
            SELECT 1;
            INSERT /* a semicolon ; in a comment */ INTO t TEMPORARY PARTITION(p1)
            SELECT * FROM s;
        """.trimIndent()
        val insert = sql.indexOf("INSERT")
        val standalone = tokens(DorisLexer(), sql.substring(insert))
        val temporary = standalone.indexOf("SQL_BLOCK_COMMENT 'TEMPORARY'")
        assertTrue("standalone INSERT must mask TEMPORARY", temporary >= 0)
        val full = tokens(DorisLexer(), sql)
        assertEquals("preceding range DDL must not affect the following INSERT", standalone,
            full.dropWhile { it != standalone.first() })
        assertRestartsMatchFullLex(::DorisLexer, sql)
    }

    fun testInsertOverwriteTemporaryPartitionTokens() {
        val sql = "INSERT OVERWRITE TABLE t TEMPORARY PARTITION(p1) SELECT * FROM s;"
        val actual = DorisLexer().lexAll(sql).filter { it.type != TokenType.WHITE_SPACE }
        val mysql = MysqlLexer().lexAll(sql).filter { it.type != TokenType.WHITE_SPACE }
        assertEquals(mysql.size, actual.size)
        for ((before, after) in mysql.zip(actual)) {
            assertEquals(before.start, after.start)
            assertEquals(before.end, after.end)
            val word = sql.substring(before.start, before.end)
            assertSame(word, if (word in setOf("OVERWRITE", "TABLE", "TEMPORARY")) SqlTokens.SQL_BLOCK_COMMENT else before.type,
                after.type)
        }
        assertRestartsMatchFullLex(::DorisLexer, sql)
    }

    fun testCastTargetsMaskQuotedAndNonWordTokens() {
        for (function in listOf("CAST", "TRY_CAST", "CONVERT")) {
            for (trivia in listOf(" ", " /* target */ ", " -- target\n ")) {
                for (target in listOf("\"text\"", "`text`", "'text'", "123")) {
                    val sql = "SELECT $function(x AS$trivia$target);"
                    assertTrue(sql, tokens(DorisLexer(), sql).contains("SQL_BLOCK_COMMENT 'AS$trivia$target'"))
                    assertRestartsMatchFullLex(::DorisLexer, sql)
                }
                val keptTarget = "SELECT $function(x AS${trivia}CHAR);"
                assertEquals(keptTarget, tokens(MysqlLexer(), keptTarget), tokens(DorisLexer(), keptTarget))
            }
        }
    }

    fun testCastWithoutTargetPreservesDelimitersAndEndOfInput() {
        for (function in listOf("CAST", "TRY_CAST", "CONVERT")) {
            for (trivia in listOf("", " ", " /* target */ ", " -- target\n ")) {
                for (tail in listOf("", "; SELECT 1;", "); SELECT 1;", ", y); SELECT 1;")) {
                    val sql = "SELECT $function(x AS$trivia$tail"
                    assertEquals(sql, tokens(MysqlLexer(), sql), tokens(DorisLexer(), sql))
                    assertRestartsMatchFullLex(::DorisLexer, sql)
                }
            }
        }
    }
}
