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
        // Doris UDF / alias functions — plain, scope-modified, and kind-modified forms all break
        // MySQL's CREATE FUNCTION grammar (which wants RETURN|BEGIN, not PROPERTIES / a modifier).
        "CREATE FUNCTION my_udf(INT) RETURNS INT PROPERTIES(\"symbol\"=\"s\");",
        "CREATE GLOBAL FUNCTION my_udf(INT) RETURNS INT PROPERTIES(\"symbol\"=\"s\");",
        "CREATE AGGREGATE FUNCTION my_agg(INT) RETURNS INT PROPERTIES(\"symbol\"=\"s\");",
        "CREATE ALIAS FUNCTION my_alias(INT) WITH PARAMETER(x) AS x + 1;",
        "CREATE GLOBAL ALIAS FUNCTION my_alias(INT) WITH PARAMETER(x) AS x + 1;",
        // DROP with a Doris scope modifier (plain DROP FUNCTION is valid MySQL — see staysMysql).
        "DROP GLOBAL FUNCTION my_udf(INT);",
    )

    /**
     * Non-Doris forms of the same lead must NOT be stolen by the lenient gate — they keep MySQL's
     * typed PSI. Keyed by a MySQL-typed marker that only appears when MySQL (not the lenient path)
     * parsed the statement.
     */
    private val staysMysql = mapOf(
        "ANALYZE TABLE t;" to "MYSQL_ANALYZE_TABLE_STATEMENT",
        "LOAD DATA INFILE 'f' INTO TABLE t;" to "MYSQL_LOAD_DATA_DML_INSTRUCTION",
        // Plain MySQL CREATE FUNCTION (no modifier, no PROPERTIES) is valid there — keep it typed.
        "CREATE FUNCTION f() RETURNS INT RETURN 1;" to "SQL_CREATE_FUNCTION_STATEMENT",
        // Plain DROP FUNCTION is valid MySQL — the scope-modifier gate must not steal it.
        "DROP FUNCTION my_udf;" to "SQL_GENERIC_DROP_STATEMENT",
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
