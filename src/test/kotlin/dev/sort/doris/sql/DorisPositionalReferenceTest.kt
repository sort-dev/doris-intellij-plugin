package dev.sort.doris.sql

import com.intellij.lang.Language
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A bare integer in GROUP BY / ORDER BY (`GROUP BY 1`, `ORDER BY 2`) is a positional ordinal — the
 * platform MySQL dialect models it as SQL_POSITIONAL_REFERENCE, not SQL_NUMERIC_LITERAL. Our replay
 * delegates GROUP/ORDER items to the platform value-expression parser, which would render the
 * integer as a plain numeric literal; [dev.sort.doris.sql.replay.CstReplayer.positionalIntegerOf]
 * intercepts it and emits the positional reference structurally. Locked to byte-parity with MySQL.
 */
class DorisPositionalReferenceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        System.setProperty("doris.replay.poc", "true")
    }

    override fun tearDown() {
        try {
            System.setProperty("doris.replay.poc", "false")
        } finally {
            super.tearDown()
        }
    }

    /** GROUP BY and ORDER BY ordinals replay byte-identical to the platform MySQL parser. */
    fun testPositionalOrdinalsMatchMysql() {
        val sql = "SELECT a, b, count(*) AS n FROM t GROUP BY 1, 2 ORDER BY 3 DESC, 1;"
        val doris = tree("DorisSQL", sql)
        val mysql = tree("MySQL", sql)
        assertEquals("positional GROUP BY / ORDER BY must replay identically to MySQL", mysql, doris)
        // Four ordinals (GROUP BY 1, 2; ORDER BY 3, 1) — each a positional reference, not a literal.
        assertEquals(4, Regex("SQL_POSITIONAL_REFERENCE").findAll(doris).count())
        assertFalse(
            "an ordinal must not remain a numeric literal",
            doris.contains("SQL_NUMERIC_LITERAL"),
        )
    }

    /** A real expression (`1 + 1`) or a LIMIT integer is NOT an ordinal — those stay numeric literals. */
    fun testNonOrdinalIntegersStayLiterals() {
        // 1+1 in GROUP BY is a constant expression, not position 2; LIMIT 5 is a plain literal.
        val sql = "SELECT a FROM t GROUP BY 1 + 1 LIMIT 5;"
        val doris = tree("DorisSQL", sql)
        assertEquals("MySQL parity for non-ordinal integers", tree("MySQL", sql), doris)
        assertFalse(
            "a GROUP BY arithmetic expression must not become a positional reference",
            doris.contains("SQL_POSITIONAL_REFERENCE"),
        )
    }

    private fun tree(langId: String, sql: String): String {
        val lang = Language.findLanguageByID(langId) ?: error("no language $langId")
        val file = PsiFileFactory.getInstance(project).createFileFromText("corpus.sql", lang, sql, false, true)!!
        return DebugUtil.psiToString(file, true).replace("\r\n", "\n")
    }
}
