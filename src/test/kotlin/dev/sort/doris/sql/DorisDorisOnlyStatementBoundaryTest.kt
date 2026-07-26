package dev.sort.doris.sql

import com.intellij.lang.Language
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Doris-only statement leads MySQL has no shape for must parse as ONE statement so the run-block
 * (statement-under-caret) can select them. Found by the 2026-07 statement-boundary sweep alongside
 * the INVERTED INDEX fix: MySQL leaves loose tokens + a PsiErrorElement and no statement wrapper, so
 * nothing is executable. These leads now route to the lenient Doris-statement path.
 */
class DorisDorisOnlyStatementBoundaryTest : BasePlatformTestCase() {

    /** Each becomes exactly one clean SQL_STATEMENT (the run-block anchor). */
    private val dorisOnly = listOf(
        "CLEAN ALL PROFILE;",
        "CLEAN LABEL lbl FROM db;",
        "CLEAN ALL QUERY STATS;",
        "UNSET VARIABLE x;",
        "UNSET DEFAULT STORAGE VAULT;",
        "LOAD LABEL db.lbl (DATA INFILE(\"s3://x\") INTO TABLE t) WITH S3 (\"k\"=\"v\");",
        "ANALYZE DATABASE db;",
        // CREATE FUNCTION with a Doris scope/kind modifier (MySQL only knows plain CREATE FUNCTION).
        "CREATE GLOBAL FUNCTION my_udf(INT) RETURNS INT PROPERTIES(\"symbol\"=\"s\");",
        "CREATE AGGREGATE FUNCTION my_agg(INT) RETURNS INT PROPERTIES(\"symbol\"=\"s\");",
        "CREATE ALIAS FUNCTION my_alias(INT) WITH PARAMETER(x) AS x + 1;",
    )

    /**
     * Non-Doris forms of the same lead must NOT be stolen by the lenient gate — they keep MySQL's
     * typed PSI. Keyed by a MySQL-typed marker that only appears when MySQL (not the lenient path)
     * parsed the statement.
     */
    private val staysMysql = mapOf(
        "ANALYZE TABLE t;" to "MYSQL_ANALYZE_TABLE_STATEMENT",
        "LOAD DATA INFILE 'f' INTO TABLE t;" to "MYSQL_LOAD_DATA_DML_INSTRUCTION",
    )

    fun testDorisOnlyLeadsAreOneCleanStatement() {
        val lang = Language.findLanguageByID("DorisSQL")!!
        for (sql in dorisOnly) {
            val tree = treeOf(lang, sql)
            assertFalse("unexpected error PSI for: $sql\n$tree", tree.contains("PsiErrorElement"))
            assertTrue(
                "expected one top-level SQL_STATEMENT for: $sql\n$tree",
                tree.startsWith("SqlFile:corpus.sql\n  SQL_STATEMENT"),
            )
        }
    }

    fun testNonDorisFormsStayMysqlTyped() {
        val lang = Language.findLanguageByID("DorisSQL")!!
        for ((sql, mysqlMarker) in staysMysql) {
            val tree = treeOf(lang, sql)
            assertTrue(
                "expected MySQL-typed $mysqlMarker (not stolen by the lenient gate) for: $sql\n$tree",
                tree.contains(mysqlMarker),
            )
        }
    }

    private fun treeOf(lang: Language, sql: String): String {
        val file = PsiFileFactory.getInstance(project).createFileFromText("corpus.sql", lang, sql, false, true)!!
        return DebugUtil.psiToString(file, true)
    }
}
