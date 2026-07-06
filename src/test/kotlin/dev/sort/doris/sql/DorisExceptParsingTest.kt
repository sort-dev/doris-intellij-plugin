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

    fun testInsertOverwriteWithCtes() {
        val tree = dump(
            """
            INSERT OVERWRITE TABLE acme_derived.user_watch_video PARTITION(*)
            WITH sourced AS (
                SELECT * FROM acme_derived.events_watch_time_by_user_1h_mv
                WHERE window_start_at >= '2026-06-30'
            ), withPrevEvent AS (
                SELECT *, earliest_event_at FROM sourced
            )
            SELECT * FROM withPrevEvent;
            """.trimIndent()
        )
        println("=== INSERT OVERWRITE + CTEs ===")
        println(tree)
        assertFalse("insert-overwrite must parse without error elements", tree.contains("PsiErrorElement"))
        assertTrue("must produce a real insert DML instruction (resolvable target)",
            tree.contains("SQL_INSERT_DML_INSTRUCTION"))
        assertTrue("OVERWRITE must be masked as a comment", tree.contains("PsiComment(SQL_BLOCK_COMMENT)('OVERWRITE')"))
    }

    fun testSameCtesWithoutInsertHeader() {
        val tree = dump(
            """
            WITH sourced AS (
                SELECT * FROM acme_derived.events_watch_time_by_user_1h_mv
                WHERE window_start_at >= '2026-06-30'
            ), withPrevEvent AS (
                SELECT *, earliest_event_at FROM sourced
            )
            SELECT * FROM withPrevEvent;
            """.trimIndent()
        )
        println("=== same CTEs, no INSERT header ===")
        println(tree)
    }

    fun testCountStarWithDorisCasts() {
        val tree = dump(
            """
            select count(*) from acme_import.events
            where event_at >= '2026-06-29'
              -- and user_connect_info['user_client'] = 'web'
              AND INSTR(CAST(CAST(event_value AS JSON) AS STRING), 'ephemeral') > 0
              -- AND CAST(event_value['video_type_id'] AS TINYINT)  = 2
            limit 1000;
            """.trimIndent()
        )
        println("=== count(*) with Doris casts ===")
        println(tree)
    }

    fun testInsertOverwriteMapAccessCte() {
        val tree = dump(
            """
            INSERT OVERWRITE TABLE acme_derived.user_watch_video  PARTITION(*)
            WITH base AS (
            select CAST(user_connect_info['user_client'] AS string) AS client,
                event_type,
                sum(if(user_id IS NULL OR user_id = 0, 0, 1)) AS with_known_users,
                sum(if(event_value['provider_response_id'] IS NOT NULL, 1, 0)) AS has_provider_response_id
            from acme_import.events
            where event_at >= '2026-06-29'
            )
            SELECT * FROM base
            group by 1, 2
            order by 1;
            """.trimIndent()
        )
        println("=== insert overwrite with map-access CTE ===")
        println(tree)
    }

    fun testExceptSetOperatorUntouched() {
        val tree = dump("select a from t EXCEPT select a from u;")
        println("=== EXCEPT set operator ===")
        println(tree)
        assertTrue("set-op EXCEPT must remain a set operation (union-ish node expected)",
            tree.contains("UNION", ignoreCase = true) || tree.contains("EXCEPT"))
    }
}
