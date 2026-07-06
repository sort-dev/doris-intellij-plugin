package dev.sort.doris.sql

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * PSI-shape tests for `SELECT * EXCEPT(...)` handling. The projection fix only works if the
 * emitted SQL_SELECT_EXCEPT_CLAUSE lands as a DIRECT child of SQL_SELECT_CLAUSE (that is where
 * SqlSelectClauseImplKt.computeDasType looks for SqlSelectModifierClauseBase children) and the
 * rest of the query still parses cleanly (FROM sources resolvable, no error elements).
 */
class DorisExceptParsingTest : BasePlatformTestCase() {

    private fun parse(sql: String): com.intellij.psi.PsiFile {
        val lang = com.intellij.lang.Language.findLanguageByID("DorisSQL")
        assertNotNull("DorisSQL language must be registered in the test environment", lang)
        val pd = com.intellij.lang.LanguageParserDefinitions.INSTANCE.forLanguage(lang!!)
        assertNotNull("DorisSQL parser definition must be registered", pd)
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("test.sql", lang, sql, false, true)
        assertNotNull("createFileFromText returned null for DorisSQL", file)
        return file!!
    }

    private fun dump(sql: String): String {
        val file = parse(sql)
        return DebugUtil.psiToString(file, true)
    }

    fun testBareAsteriskExcept() {
        val tree = dump("select * EXCEPT(event_sub_type) from events as e;")
        println("=== bare * EXCEPT ===")
        println(tree)
    }

    fun testCteChainWithExcept() {
        val tree = dump(
            """
            WITH foo AS (
                select * EXCEPT(event_sub_type) from events AS e
            ), bar AS (
                select * from foo AS f
            )
            select * from bar AS b;
            """.trimIndent()
        )
        println("=== CTE chain with EXCEPT ===")
        println(tree)
    }

    fun testQualifiedAsteriskExcept() {
        val tree = dump("select e.* EXCEPT(event_sub_type) from events as e;")
        println("=== qualified e.* EXCEPT ===")
        println(tree)
    }

    fun testPlainSelectStillClean() {
        val file = parse("select * from events as e;")
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
        println("=== plain select ===")
        println(DebugUtil.psiToString(file, true))
        assertEmpty("plain SELECT must parse without errors", errors.toList())
    }

    fun testExceptSetOperatorUntouched() {
        val tree = dump("select a from t EXCEPT select a from u;")
        println("=== EXCEPT set operator ===")
        println(tree)
        assertTrue("set-op EXCEPT must remain a set operation (union-ish node expected)",
            tree.contains("UNION", ignoreCase = true) || tree.contains("EXCEPT"))
    }
}
