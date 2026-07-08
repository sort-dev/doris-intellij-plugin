package dev.sort.doris.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Pins the [DorisHighlightInfoFilter] suppression rules from the 2026-07-08 dogfood batch against
 * REAL daemon highlighting (SqlResolveInspection + SqlInsertValuesInspection enabled, dialect mapped
 * to DorisSQL — the same pipeline the IDE runs). Each suppression is pinned BOTH ways:
 * the Doris-legal construct must be quiet, and the genuinely-wrong twin must KEEP its error.
 *
 * Flag-ON cases (`doris.replay.poc`) cover the Route B replay shapes (SWITCH / CREATE JOB /
 * REFRESH MV PARTITION / MTMV schedule / CTAS / TVF-in-FROM); the flag is cleared after each case.
 */
class DorisHighlightingSuppressionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(
            com.intellij.sql.inspections.SqlResolveInspection(),
            com.intellij.sql.inspections.SqlInsertValuesInspection(),
            com.intellij.sql.inspections.SqlAmbiguousColumnInspection(),
        )
    }

    override fun tearDown() {
        try {
            System.clearProperty(REPLAY_FLAG)
        } finally {
            super.tearDown()
        }
    }

    private var counter = 0

    /** Highlight [sql] as a DorisSQL file; returns the visible (post-filter) warnings+errors. */
    private fun highlight(sql: String, replay: Boolean = false): List<HighlightInfo> {
        if (replay) System.setProperty(REPLAY_FLAG, "true") else System.clearProperty(REPLAY_FLAG)
        try {
            val psi = myFixture.configureByText("h${counter++}.sql", sql)
            SqlDialectMappings.getInstance(project).setMapping(psi.virtualFile, DorisSqlDialect.INSTANCE)
            return myFixture.doHighlighting()
                .filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }
        } finally {
            System.clearProperty(REPLAY_FLAG)
        }
    }

    private fun assertNoInfo(infos: List<HighlightInfo>, needle: String) {
        val hits = infos.filter { it.description.contains(needle) }
        assertTrue(
            "expected no highlight containing '$needle' but got: " +
                hits.joinToString { "'${it.description}'" },
            hits.isEmpty(),
        )
    }

    private fun assertHasInfo(infos: List<HighlightInfo>, needle: String) {
        assertTrue(
            "expected a highlight containing '$needle' but got only: " +
                infos.joinToString { "'${it.description}'" },
            infos.any { it.description.contains(needle) },
        )
    }

    // --- P0: `* EXCEPT(...)` count-mismatch suppression (SqlInsertValuesInspection) ---

    fun testInsertSelectExceptSuppressesCountMismatch() {
        val infos = highlight(
            "INSERT INTO acme_db.tgt (a, b) " +
                "WITH src AS (SELECT 1 AS x, 2 AS y, 3 AS z) SELECT * EXCEPT(z) FROM src;",
        )
        assertNoInfo(infos, COUNT_MISMATCH)
    }

    fun testInsertSelectWithoutExceptKeepsGenuineCountMismatch() {
        val infos = highlight(
            "INSERT INTO acme_db.tgt (a, b) " +
                "WITH src AS (SELECT 1 AS x, 2 AS y, 3 AS z) SELECT * FROM src;",
        )
        assertHasInfo(infos, COUNT_MISMATCH)
    }

    fun testInsertValuesKeepsGenuineCountMismatch() {
        // VALUES form, no EXCEPT anywhere: the classic arity error must stay.
        val infos = highlight("INSERT INTO acme_db.tgt (a, b) VALUES (1, 2, 3);")
        assertHasInfo(infos, COUNT_MISMATCH)
    }

    fun testExceptSetOperatorDoesNotGateSuppression() {
        // `EXCEPT (SELECT ...)` is the set operator, not column exclusion — mismatches stay.
        val infos = highlight(
            "INSERT INTO acme_db.tgt (a, b) " +
                "WITH src AS (SELECT 1 AS x, 2 AS y, 3 AS z) SELECT * FROM src EXCEPT (SELECT 1, 2, 3);",
        )
        assertHasInfo(infos, COUNT_MISMATCH)
    }

    fun testCreateTableColumnListSelectExceptSuppressed() {
        // CREATE TABLE column-list vs AS-query is the same message family and the same
        // statement-level gate as the INSERT case (the suppression keys on message + statement text,
        // not on the statement kind). NB the platform inspection does not fire for CTAS in this
        // fixture (probed 2026-07-08: the genuine-mismatch twin is also quiet with no data source),
        // so this test pins the user-facing quietness only; the both-ways pin lives on the INSERT
        // tests above, which share the exact code path.
        val except = highlight(
            "CREATE TABLE tgt (a, b) AS " +
                "WITH src AS (SELECT 1 AS x, 2 AS y, 3 AS z) SELECT * EXCEPT(z) FROM src;",
        )
        assertNoInfo(except, COUNT_MISMATCH)
    }

    // --- P1 (0.4.0) EXCEPT flavor a: UNION operand-count vs `* EXCEPT` ---

    fun testUnionOperandCountWithExceptSuppressed() {
        // Server counts: left `* EXCEPT(b)` = 1 column, right = 1 — legal. The platform can't
        // subtract the masked EXCEPT list, counts 2 vs 1, and fires the operand-count error.
        val infos = highlight(
            "SELECT * EXCEPT(b) FROM (SELECT 1 AS a, 2 AS b) t UNION ALL SELECT 1;",
        )
        assertNoInfo(infos, UNION_COUNT_MISMATCH)
    }

    fun testUnionOperandCountWithExceptInRightBranchSuppressed() {
        val infos = highlight(
            "SELECT 1 UNION ALL SELECT * EXCEPT(b) FROM (SELECT 1 AS a, 2 AS b) t;",
        )
        assertNoInfo(infos, UNION_COUNT_MISMATCH)
    }

    fun testGenuineUnionOperandCountStaysRed() {
        val infos = highlight(
            "SELECT a, b FROM (SELECT 1 AS a, 2 AS b) t UNION ALL SELECT 1;",
        )
        assertHasInfo(infos, UNION_COUNT_MISMATCH)
    }

    fun testUnionOperandCountGateIsScopedToTheSetOperation() {
        // An `* EXCEPT` in the SAME STATEMENT but outside both union branches (in the CTE) must
        // not vouch for a genuine 1-vs-2 mismatch between EXCEPT-free branches.
        val infos = highlight(
            "WITH s AS (SELECT * EXCEPT(b) FROM (SELECT 1 AS a, 2 AS b) q) " +
                "SELECT a FROM s UNION ALL SELECT 1, 2;",
        )
        assertHasInfo(infos, UNION_COUNT_MISMATCH)
    }

    // --- P1 (0.4.0) EXCEPT flavor b: ambiguity from explicit column + masked `* EXCEPT` star ---

    fun testAmbiguityFromExceptMaskedStarSuppressed() {
        // `SELECT CreateTime, * EXCEPT(CreateTime)` — the server de-duplicates (CreateTime arrives
        // once), the platform expands the masked star to ALL columns and sees CreateTime twice.
        val infos = highlight(
            "SELECT CreateTime FROM (SELECT CreateTime, * EXCEPT(CreateTime) " +
                "FROM (SELECT 1 AS CreateTime, 2 AS b) s) t;",
        )
        assertNoInfo(infos, AMBIGUOUS_COLUMN)
    }

    fun testGenuineAmbiguityStaysRedWhenNameNotInExceptList() {
        // The query HAS an `* EXCEPT`, but the duplicated name is NOT in the list — the server
        // really does project CreateTime twice; the ambiguity is genuine and must stay red.
        val infos = highlight(
            "SELECT CreateTime FROM (SELECT CreateTime, * EXCEPT(b) " +
                "FROM (SELECT 1 AS CreateTime, 2 AS b) s) t;",
        )
        assertHasInfo(infos, AMBIGUOUS_COLUMN)
    }

    // --- P2-1: unknown session variables in SET ---

    fun testSetSessionVariableQuiet() {
        val infos = highlight("SET enable_local_shuffle = true;")
        assertNoInfo(infos, "Unable to resolve")
    }

    // --- P2-2: USE @compute_group goes lenient (parse-level; no user-variable reference) ---

    fun testUseComputeGroupQuiet() {
        val infos = highlight("use @etl;\nSELECT 1;\nUSE acme_db@etl;\nSELECT 2;")
        assertNoInfo(infos, "Unable to resolve")
    }

    // --- P2 replay shapes: SWITCH / CREATE JOB / REFRESH MV PARTITION / MTMV schedule ---

    fun testSwitchCatalogQuietUnderReplay() {
        val infos = highlight("SWITCH internal;\nSELECT 1;", replay = true)
        assertNoInfo(infos, "Unable to resolve symbol 'internal'")
    }

    fun testCreateJobNameQuietUnderReplay() {
        val infos = highlight(
            "CREATE JOB etl_backfill_job ON SCHEDULE AT CURRENT_TIMESTAMP " +
                "DO INSERT INTO acme_db.t SELECT * FROM acme_db.s;",
            replay = true,
        )
        assertNoInfo(infos, "Unable to resolve symbol 'etl_backfill_job'")
    }

    fun testRefreshMvPartitionNameQuietUnderReplay() {
        val infos = highlight(
            "REFRESH MATERIALIZED VIEW acme_derived.events_rollup_mv PARTITION(p_20260617);",
            replay = true,
        )
        assertNoInfo(infos, "Unable to resolve symbol 'p_20260617'")
    }

    fun testMtmvScheduleTailQuietUnderReplay() {
        val infos = highlight(
            """
            CREATE MATERIALIZED VIEW mv_orders_daily
            BUILD IMMEDIATE
            REFRESH AUTO ON SCHEDULE EVERY 1 HOUR
            DISTRIBUTED BY HASH(order_id) BUCKETS 10
            PROPERTIES ('replication_num' = '1')
            AS SELECT order_id, count(*) FROM acme_db.orders GROUP BY order_id;
            """.trimIndent(),
            replay = true,
        )
        assertNoInfo(infos, "Unable to resolve symbol 'HOUR'")
    }

    // --- P2-6: GROUP BY <select-alias> ---

    fun testGroupBySelectAliasQuiet() {
        val infos = highlight(
            "WITH t AS (SELECT 1 AS fid) " +
                "SELECT (fid DIV 10) % 1000 AS bucket, count(*) AS c FROM t GROUP BY bucket;",
        )
        assertNoInfo(infos, "Unable to resolve column 'bucket'")
    }

    fun testGroupByWrongNameStaysRed() {
        val infos = highlight(
            "WITH t AS (SELECT 1 AS fid) " +
                "SELECT (fid DIV 10) % 1000 AS bucket, count(*) AS c FROM t GROUP BY buckit;",
        )
        assertHasInfo(infos, "Unable to resolve column 'buckit'")
    }

    // --- P2-10: CTAS key/order columns + ENGINE = OLAP ---

    fun testCtasKeyColumnsQuietUnderReplay() {
        val infos = highlight(
            """
            CREATE TABLE acme_db.t2
            ENGINE = OLAP
            UNIQUE KEY(id)
            ORDER BY(created_at)
            DISTRIBUTED BY HASH(id) BUCKETS 10
            PROPERTIES ('replication_num' = '1')
            AS WITH src AS (SELECT 1 AS id, 2 AS created_at) SELECT id, created_at FROM src;
            """.trimIndent(),
            replay = true,
        )
        assertNoInfo(infos, "Unable to resolve symbol 'OLAP'")
        assertNoInfo(infos, "Unable to resolve column 'id'")
        assertNoInfo(infos, "Unable to resolve column 'created_at'")
    }

    fun testCreateTableWithColumnsKeepsKeyValidation() {
        // NOT a CTAS: declared columns exist, so a bogus key column must stay red (flag-on replay
        // shape; the column defs resolve for real, `wrong_col` matches none of them).
        val infos = highlight(
            """
            CREATE TABLE acme_db.t3 (id BIGINT, name VARCHAR(64))
            DUPLICATE KEY(wrong_col)
            DISTRIBUTED BY HASH(id) BUCKETS 10
            PROPERTIES ('replication_num' = '1');
            """.trimIndent(),
            replay = true,
        )
        assertHasInfo(infos, "Unable to resolve column 'wrong_col'")
    }

    // --- P1 evidence (item 9): TVFs in FROM must keep MySQL delegation under replay ---

    fun testTableFunctionsQuietUnderReplay() {
        val infos = highlight(
            "SELECT * FROM tasks('type'='insert');\n" +
                "SELECT * FROM jobs('type'='insert');\n" +
                "SELECT * FROM S3('uri'='s3://acme-bucket/x.parquet');",
            replay = true,
        )
        assertNoInfo(infos, "Unable to resolve")
    }

    // --- P3 (0.4.0): LATERAL VIEW [POS]EXPLODE alias references (dogfood item: `item` red) ---

    fun testLateralViewAliasRefsQuietUnderReplay() {
        val infos = highlight(
            "WITH t AS (SELECT 1 AS arr) " +
                "SELECT pos, item FROM t LATERAL VIEW POSEXPLODE(arr) tv AS pos, item;",
            replay = true,
        )
        assertNoInfo(infos, "Unable to resolve")
    }

    fun testLateralViewAliasRefsQuietFlagOff() {
        val infos = highlight(
            "WITH t AS (SELECT 1 AS arr) " +
                "SELECT pos, item FROM t LATERAL VIEW POSEXPLODE(arr) tv AS pos, item;",
        )
        assertNoInfo(infos, "Unable to resolve")
    }

    fun testLateralViewAliasInsideExpressionQuiet() {
        // The dogfood shape: the alias consumed inside nested calls (NULLIF(JSON_EXTRACT(item,...))).
        val infos = highlight(
            "WITH t AS (SELECT 1 AS payload_arr) " +
                "SELECT NULLIF(JSON_EXTRACT(item, '\$.k'), 'null') AS k " +
                "FROM t LATERAL VIEW EXPLODE(payload_arr) tv AS item;",
            replay = true,
        )
        assertNoInfo(infos, "Unable to resolve")
    }

    fun testLateralViewWrongNameStaysRed() {
        // `bogus` matches no lateral-view alias and sits outside the construct — must stay red.
        val infos = highlight(
            "WITH t AS (SELECT 1 AS arr) " +
                "SELECT bogus FROM t LATERAL VIEW EXPLODE(arr) tv AS item;",
            replay = true,
        )
        assertHasInfo(infos, "Unable to resolve column 'bogus'")
    }

    fun testWrongNameWithoutLateralViewStillRedDespiteAliasWord() {
        // Same names, NO lateral view in the statement: the alias gate must not vouch.
        val infos = highlight(
            "WITH t AS (SELECT 1 AS arr) SELECT item FROM t;",
            replay = true,
        )
        assertHasInfo(infos, "Unable to resolve column 'item'")
    }

    // --- guard: ordinary wrong column names keep their error (no over-suppression) ---

    fun testGenuineUnresolvedColumnStaysRed() {
        val infos = highlight("WITH t AS (SELECT 1 AS a) SELECT b FROM t;")
        assertHasInfo(infos, "Unable to resolve column 'b'")
    }

    private companion object {
        const val REPLAY_FLAG = "doris.replay.poc"
        const val COUNT_MISMATCH = "value(s) expected, got"
        const val UNION_COUNT_MISMATCH = "operands should have the same number of columns"
        const val AMBIGUOUS_COLUMN = "Ambiguous column reference"
    }
}
