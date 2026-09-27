package dev.sort.doris.sql

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.EvaluationHelper
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.dialects.mysql.MysqlDialect
import com.intellij.sql.psi.SqlSelectIntoClause
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The console runs a statement through `executeQuery()` when the dialect's evaluation helper finds a
 * result-set query without a SELECT INTO clause. Doris answers `INTO OUTFILE` without a result set,
 * so Connector/J then fails with S1009. The replayed Doris PSI must carry SQL_SELECT_INTO_CLAUSE.
 */
class DorisOutfileExecutionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            System.setProperty(DorisReplay.PROPERTY, "false")
        } finally {
            super.tearDown()
        }
    }

    fun testOutfileStatementsAreNotRunAsResultSetQueries() {
        val replayOnly = setOf(CTE)
        for (replay in listOf(true, false)) {
            System.setProperty(DorisReplay.PROPERTY, replay.toString())
            assertNotNull("replay=$replay: a plain query keeps executeQuery()", resultSetQuery("select * from foo"))
            for (sql in OUTFILE_FORMS) {
                if (!replay && sql in replayOnly) continue
                assertNull("replay=$replay must not treat as a result-set query: $sql", resultSetQuery(sql))
            }
        }
    }

    fun testReplayedOutfileClauseMatchesThePlatformShape() {
        val sql = "select * from foo INTO OUTFILE 's3://bucket/x_'"
        val platform = DebugUtil.psiToString(PsiFileFactory.getInstance(project)
            .createFileFromText("outfile.sql", MysqlDialect.INSTANCE, sql, false, true)!!, true)
        System.setProperty(DorisReplay.PROPERTY, "true")
        assertEquals(platform, tree(sql))
    }

    fun testReplayedDorisOutfileOptionsStayInsideTheClause() {
        System.setProperty(DorisReplay.PROPERTY, "true")
        for (sql in OUTFILE_FORMS) {
            val file = parse(sql)
            assertEmpty("$sql\n${DebugUtil.psiToString(file, true)}", PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
            val clause = PsiTreeUtil.findChildrenOfType(file, SqlSelectIntoClause::class.java).single()
            assertTrue(sql, clause.text.startsWith("INTO OUTFILE"))
            assertTrue("the clause must reach the end of the statement: $sql", sql.endsWith(clause.text))
        }
    }

    fun testReplayedOutfileOptionsAreNotHighlighted() {
        System.setProperty(DorisReplay.PROPERTY, "true")
        myFixture.enableInspections(com.intellij.sql.inspections.SqlResolveInspection())
        for ((index, sql) in OUTFILE_FORMS.withIndex()) {
            val psi = myFixture.configureByText("outfile$index.sql", sql)
            SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DorisSqlDialect.INSTANCE)
            val clauseStart = sql.indexOf("INTO OUTFILE")
            val inClause = myFixture.doHighlighting()
                .filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }
                .filter { it.startOffset >= clauseStart }
            assertEmpty("$sql: ${inClause.joinToString { it.description }}", inClause)
        }
    }

    private fun resultSetQuery(sql: String) = EvaluationHelper.EP.forLanguage(DorisSqlDialect.INSTANCE)
        .parseQueryResultSetExpression(project, DorisSqlDialect.INSTANCE, sql, DorisSqlDialect.INSTANCE)

    private fun parse(sql: String): PsiFile = PsiFileFactory.getInstance(project)
        .createFileFromText("outfile.sql", DorisSqlDialect.INSTANCE, sql, false, true)!!

    private fun tree(sql: String): String = DebugUtil.psiToString(parse(sql), true)

    private companion object {
        const val CTE = "with t as (select 1 a) select * from t INTO OUTFILE 's3://b/x_' FORMAT AS PARQUET"
        val OUTFILE_FORMS = listOf(
            "select * from foo INTO OUTFILE 's3://bucket/x_'",
            "select * from foo INTO OUTFILE 's3://bucket/x_' FORMAT AS CSV",
            "select * from foo INTO OUTFILE 's3://bucket/x_' FORMAT AS PARQUET PROPERTIES ('s3.endpoint' = 'e')",
            "select * from foo INTO OUTFILE \"file:///tmp/x_\" FORMAT AS CSV PROPERTIES (\"column_separator\" = \",\")",
            "select a from foo where a > 1 order by a limit 10 INTO OUTFILE 's3://b/x_' FORMAT AS CSV PROPERTIES ('s3.endpoint' = 'e')",
            CTE,
        )
    }
}
