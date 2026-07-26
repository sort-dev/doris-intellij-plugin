package dev.sort.doris.sql

import com.intellij.lang.Language
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Doris full-text index-component DDL — `CREATE/DROP/SHOW INVERTED INDEX ANALYZER|TOKENIZER|
 * TOKEN_FILTER` — must parse as ONE statement so the run-block (statement-under-caret) can select
 * it. MySQL's grammar errors at `INVERTED` and leaves no statement wrapper (loose CREATE +
 * PsiErrorElement + DUMMY_BLOCKs directly under the file), so nothing is executable; the plugin now
 * routes these leads to its lenient Doris-statement path (one boundary-preserving SQL_STATEMENT).
 */
class DorisInvertedIndexStatementTest : BasePlatformTestCase() {

    private val statements = listOf(
        "CREATE INVERTED INDEX ANALYZER my_analyzer PROPERTIES(\"tokenizer\"=\"standard\");",
        "CREATE INVERTED INDEX TOKENIZER my_tok PROPERTIES(\"type\"=\"ngram\");",
        "CREATE INVERTED INDEX TOKEN_FILTER my_tf PROPERTIES(\"type\"=\"lowercase\");",
        "DROP INVERTED INDEX ANALYZER my_analyzer;",
        "DROP INVERTED INDEX TOKENIZER my_tok;",
        "DROP INVERTED INDEX TOKEN_FILTER my_tf;",
        "SHOW INVERTED INDEX ANALYZER;",
    )

    fun testEachIsOneCleanStatementBlock() {
        val lang = Language.findLanguageByID("DorisSQL")!!
        for (sql in statements) {
            val file = PsiFileFactory.getInstance(project).createFileFromText("corpus.sql", lang, sql, false, true)!!
            val tree = DebugUtil.psiToString(file, true)
            assertFalse("unexpected error PSI for: $sql\n$tree", tree.contains("PsiErrorElement"))
            // The file's first child must be a single statement spanning the DDL (the run-block anchor).
            assertTrue(
                "expected a single top-level SQL_STATEMENT for: $sql\n$tree",
                tree.startsWith("SqlFile:corpus.sql\n  SQL_STATEMENT"),
            )
        }
    }
}
