package dev.sort.doris.sql

import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression suite: one test per bug found during dogfooding (see the repo history for each fix).
 * Two levels of guarantee:
 *  - [assertClean]  — parses with ZERO error elements (full structure, resolution-safe)
 *  - [assertOneStatement] — parse errors tolerated (hidden in the IDE) but the statement/run-block
 *    boundary must hold: exactly N top-level statements, nothing split or swallowed.
 */
class DorisRegressionTest : BasePlatformTestCase() {

    private fun tree(sql: String): String {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("r.sql", com.intellij.lang.Language.findLanguageByID("DorisSQL")!!, sql, false, true)
        return DebugUtil.psiToString(file!!, true)
    }

    private fun statementCount(tree: String): Int =
        Regex("^  SQL_[A-Z_]*STATEMENT", RegexOption.MULTILINE).findAll(tree).count()

    private fun assertClean(sql: String, statements: Int = 1) {
        val t = tree(sql)
        val errors = Regex("PsiErrorElement").findAll(t).count()
        if (errors > 0) println(t)
        assertEquals("statement count", statements, statementCount(t))
        assertEquals("parse errors", 0, errors)
    }

    private fun assertOneStatement(sql: String, statements: Int = 1) {
        val t = tree(sql)
        assertEquals("statement count (block boundary)", statements, statementCount(t))
    }

    // --- window functions (empty function map was the root cause; fixed in DorisSqlDialect) ---

    fun testMultiLineLagTopLevel() = assertClean(
        """
        WITH s AS (SELECT 1 AS ts, 2 AS k)
        SELECT *,
            LAG(ts) OVER (PARTITION BY k ORDER BY ts) AS prev FROM s;
        """.trimIndent()
    )

    fun testRealWatchTimeLagQuery() = assertClean(
        """
        WITH sourced AS (
            SELECT *
            FROM acme_derived.events_watch_time_by_user_1h_mv
            where window_start_at >= '2026-06-30'
              AND window_start_at < '2026-07-02'
        )
        SELECT *,
            LAG(earliest_event_at) OVER (PARTITION BY watch_rollup_type, watch_rollup_key, viewing_user_client_key,
                                              correlation_search_correlator_key, video_id,
                                              video_is_placeholder,
                                              video_paywall_type_id
                                 ORDER BY earliest_event_at, latest_event_at, event_key
                                ) AS _prev_event_at
        FROM sourced;
        """.trimIndent()
    )

    fun testScalarSubqueryIntervalArithmetic() = assertClean(
        """
        WITH dateRange AS (SELECT '2026-06-30' AS d)
        SELECT * FROM events
        WHERE created_at >= (SELECT d FROM dateRange) - INTERVAL 1 DAY;
        """.trimIndent()
    )

    fun testRealInsertOverwriteLagPipeline() = assertClean(
        """
        INSERT OVERWRITE TABLE acme_derived.user_watch_video PARTITION(*)
        WITH dateRange AS (
            SELECT '2026-06-30' AS _startRangeInclusive,
                   '2026-07-05' AS _endRangeExclusive
        ), sourced AS (
            SELECT *
            FROM acme_derived.events_watch_time_by_user_1h_mv
            where window_start_at >= (SELECT _startRangeInclusive FROM dateRange) - INTERVAL 1 DAY
              AND window_start_at < (SELECT _endRangeExclusive FROM dateRange) + INTERVAL 1 DAY
        ), withPrevEvent AS (
            SELECT *,
                LAG(earliest_event_at) OVER (PARTITION BY watch_rollup_type, watch_rollup_key,
                                                  viewing_user_client_key, video_id
                                     ORDER BY earliest_event_at, latest_event_at, event_key
                                    ) AS _prev_event_at
            FROM sourced
        ), sessionMarkPhase0 AS (
            SELECT *,
                CASE
                    WHEN _prev_event_at IS NULL THEN 1
                    WHEN TIMESTAMPDIFF(HOUR, _prev_event_at, earliest_event_at) > 4 THEN 1
                    ELSE 0
                END AS _real_reset_flag
            FROM withPrevEvent
        )
        SELECT * FROM sessionMarkPhase0;
        """.trimIndent()
    )

    // --- IF() / reserved-word functions ---

    fun testIfInSelectListMultiLine() = assertClean(
        """
        SELECT
            date_trunc(c.event_at, 'hour')                  AS window_start_at,
            c.surface,
            IF(c.user_id IS NULL OR c.user_id = 0, 'A', 'U')  AS user_rollup_type,
            c.user_client_name,
            c.user_is_premium                                 AS is_premium,
            re.engine,
            COUNT(*)                                          AS click_count
        FROM acme_import.events_clicks_to_video c
        JOIN acme_bi.recommender_engines re
            ON  array_contains(c.experiments, 'src_v1_homepage-player-lineup')
        WHERE c.possible_bot_user_agent = 0
          AND c.possible_bot_cidr = 0
        GROUP BY 1, 2, 3, 4, 5, 6;
        """.trimIndent()
    )

    fun testRegexpFunctionForm() = assertClean(
        "SELECT REGEXP(user_agent, '(?i)bot|crawler') AS is_bot FROM events;"
    )

    // --- CAST / TRY_CAST (Doris-only targets masked at the lexer) ---

    fun testDorisCastTargets() = assertClean(
        """
        SELECT CAST(v AS STRING), CAST(n AS LARGEINT), CAST(j AS JSON), CAST(c AS CHAR),
               TRY_CAST(e AS ARRAY<TEXT>), TRY_CAST(m AS MAP<STRING, INT>)
        FROM t;
        """.trimIndent()
    )

    fun testCountStarWithNestedCasts() = assertClean(
        """
        select count(*) from acme_import.events
        where event_at >= '2026-06-29'
          AND INSTR(CAST(CAST(event_value AS JSON) AS STRING), 'ephemeral') > 0
        limit 1000;
        """.trimIndent()
    )

    fun testMapBracketAccess() = assertClean(
        "select CAST(user_connect_info['user_client'] AS string) AS client from events;"
    )

    // --- * EXCEPT(...) (masked at the lexer) ---

    fun testExceptCteChainResolvesStructure() = assertClean(
        """
        with foo as (
            select * EXCEPT(event_sub_type) from events AS e
        ), bar AS (
            select * from foo AS f
        )
        select * from bar AS b;
        """.trimIndent()
    )

    fun testExceptSetOperatorStillSetOp() {
        val t = tree("select a from t EXCEPT select a from u;")
        assertTrue("must remain a set operation", t.contains("SQL_UNION_EXPRESSION"))
    }

    fun testInsertIntoColumnsSelectExcept() = assertClean(
        "INSERT INTO t (a, b, c) SELECT * EXCEPT(z) FROM s;"
    )

    // --- INSERT OVERWRITE (header masked at the lexer -> real insert PSI) ---

    fun testInsertOverwriteVariants() {
        assertClean("INSERT OVERWRITE TABLE db.t PARTITION(*) SELECT * FROM s;")
        assertClean("insert overwrite table t select * from s;")
        assertClean("INSERT OVERWRITE TABLE t PARTITION(p1, p2) SELECT * FROM s;")
    }

    fun testInsertOverwriteRealInsertPsi() {
        val t = tree("INSERT OVERWRITE TABLE db.t PARTITION(*) SELECT * FROM s;")
        assertTrue("must be a real insert DML instruction", t.contains("SQL_INSERT_DML_INSTRUCTION"))
    }

    // --- CREATE VIEW (lenient prefix + real query tail) ---

    fun testCreateViewModernBody() = assertOneStatement(
        """
        CREATE OR REPLACE VIEW v_modern AS
        SELECT * EXCEPT (raw_json),
               REGEXP(user_agent, '(?i)bot|crawler') AS is_bot,
               FROM_SECOND(db_ts) AS event_at,
               JSON_OBJECT('ip', user_ip, 'os', user_os) AS info
        FROM events;
        """.trimIndent()
    )

    fun testCreateViewLateralViewBlockHolds() = assertOneStatement(
        """
        CREATE OR REPLACE VIEW acme_bi.unique_experiments AS
        SELECT tag, COUNT(*) AS row_count
        FROM acme_derived.events_watch_time_window_1d
        LATERAL VIEW EXPLODE(experiments) e AS tag
        WHERE window_start_at >= CURRENT_TIMESTAMP - interval 6 month
        GROUP BY tag
        HAVING row_count > 1000
        ORDER BY row_count DESC;
        """.trimIndent()
    )

    // --- Doris statements (lenient; block boundary is the contract) ---

    fun testCreateJob() = assertOneStatement(
        """
        CREATE JOB backfill_2026_07_0b
        ON SCHEDULE AT CURRENT_TIMESTAMP
        DO
        INSERT INTO acme_import.events
        SELECT /*+ SET_VAR(query_timeout = 28800) */ * EXCEPT(year,month,day)
        FROM  acme_pipeline.pipe_ingest_event_at_landing
        WHERE year = 2026 AND month = 7 AND day in (3,4,5);
        """.trimIndent()
    )

    fun testDorisAdminStatements() {
        assertOneStatement("REFRESH TABLE hive_archive.acme_archive.events;")
        assertOneStatement(
            """
            WARM UP COMPUTE GROUP etl
            WITH TABLE acme_derived.events_watch_time_by_user_1h_mv
                AND TABLE acme_derived.events_watch_time_window_1d_mv;
            """.trimIndent()
        )
        assertOneStatement(
            """
            CREATE DATABASE hive_tpa6.outbox
            PROPERTIES (
                'location' = 's3://outbox-starrocks/outbox'
            );
            """.trimIndent()
        )
        assertOneStatement(
            """
            CREATE TABLE t2 (id BIGINT, name VARCHAR(64))
            DISTRIBUTED BY HASH(id) BUCKETS 10 PROPERTIES ("replication_num" = "1");
            """.trimIndent()
        )
    }

    fun testQualifyBlockHolds() = assertOneStatement(
        "SELECT k, v FROM t QUALIFY ROW_NUMBER() OVER (PARTITION BY k ORDER BY v) = 1;"
    )

    fun testTableFunctionBlockHolds() = assertOneStatement(
        """
        SELECT Name, State FROM mv_infos('database'='acme_derived')
        WHERE Name = 'events_watch_time_by_user_1h_mv';
        """.trimIndent()
    )

    // --- external catalogs: 3-part names + catalog statements ---

    fun testThreePartCatalogNames() {
        assertClean("SELECT * FROM hive_archive.acme_archive.events WHERE dt = '2026-01-01';")
        assertClean(
            "SELECT e.a, i.b FROM hive_archive.acme_archive.events e " +
            "JOIN internal.acme_import.events i ON e.k = i.k;"
        )
        assertClean("INSERT INTO hive_archive.acme_archive.events SELECT * FROM s;")
    }

    fun testSwitchCatalogBoundary() = assertOneStatement(
        "SWITCH hive_archive;\nSELECT 1;",
        statements = 2
    )

    fun testUseCatalogDotDb() = assertClean(
        "USE hive_archive.acme_archive;\nSELECT 1;",
        statements = 2
    )

    // --- multi-statement boundaries: nothing splits, nothing gets swallowed ---

    fun testFiveStatementBoundaries() = assertOneStatement(
        """
        CREATE OR REPLACE VIEW v1 AS SELECT * EXCEPT(x) FROM t;
        SELECT 1;
        CREATE VIEW v2 AS SELECT a FROM t;
        INSERT OVERWRITE TABLE t SELECT * FROM s;
        SELECT count(*) FROM t;
        """.trimIndent(),
        statements = 5
    )

    fun testLenientStatementDoesNotEatNext() = assertOneStatement(
        """
        REFRESH TABLE a.b;
        SELECT 1;
        WARM UP COMPUTE GROUP g WITH TABLE x.y;
        SELECT 2;
        """.trimIndent(),
        statements = 4
    )
}
