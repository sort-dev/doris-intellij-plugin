package dev.sort.doris.sql

import com.intellij.lang.Language
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Gate 2 of RESEARCH-when-hell-freezes-over-parser.md: proof that the Route B ANTLR shadow-replay
 * bridge ([dev.sort.doris.sql.replay.CstReplayer]) can reproduce the platform MySQL dialect's PSI
 * tree BYTE-FOR-BYTE for the baseline SELECT family, using the authoritative Doris grammar as the
 * shape oracle.
 *
 * The bridge is dormant in production; this test enables it via the `doris.replay.poc` system
 * property (set in [setUp], cleared in [tearDown]) so the rest of the suite is unaffected. We assert
 * against the exact recorded MySQL golden for the same corpus SQL, so the comparison is honest: the
 * replayed DorisSQL tree must equal what the raw platform MySQL parser produced.
 */
class DorisReplayPocTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        System.setProperty(FLAG, "true")
    }

    override fun tearDown() {
        try {
            // Restore the suite baseline (flag-OFF, set in build.gradle.kts) rather than clearing:
            // since 0.5.0 the flag's UNSET default is ON (DorisReplay), so a bare clear would flip
            // every subsequently-run test onto the replay path and break the flag-off goldens.
            System.setProperty(FLAG, "false")
        } finally {
            super.tearDown()
        }
    }

    /**
     * The replay COVERAGE MANIFEST: every corpus file here replays BYTE-IDENTICAL to its recorded
     * MySQL golden with the flag on. This is the honest measure of Route B's reach — the Doris ANTLR
     * CST, replayed onto the platform token stream, reproducing the platform MySQL parser's exact PSI.
     * Only add a file once it is green; anything not here either isn't attempted yet or hit a
     * documented blocker (see below). The whole set is diffed in one pass so every regression is
     * reported together.
     *
     * ## Gate 2.5 — statement structure by replay, expressions by delegation
     *
     * The manifest now spans the ENTIRE query family of the mysql-core corpus (01–32). The unlock was
     * mid-replay delegation ([dev.sort.doris.sql.replay.CstReplayer]): the replayer reproduces the
     * statement STRUCTURE (clauses, joins, unions, CTEs, table refs, the synthetic table-expression)
     * from the CST, but at each OUTERMOST EXPRESSION it hands the builder to the platform's own
     * value-expression parser (MysqlParser.parseValueExpression). That reproduces function calls
     * (COUNT(*)'s "INFO:[expr:any*]"/"INFO:[0]" frames included), CASE / BETWEEN / IN, scalar
     * subqueries, INTERVAL arithmetic, window functions, etc. byte-for-byte — because it is literally
     * the code that emitted the golden. See the Route B / Gate 2.5 note in the research doc.
     *
     * Statement long-tail (Part 2): UPDATE and DELETE now replay for the forms whose platform shape is
     * pinned (single-table UPDATE, DELETE [USING]); every other variant declines inside CstReplayer
     * (see updateDeleteReplayable). Still NOT in scope: INSERT/REPLACE/CREATE(MySQL-shaped)/ALTER/
     * TRUNCATE/EXPLAIN/SET/transaction (mysql-core 35–44) — those keep their existing delegation/
     * lenient handling. Error-recovery corpora (the edge suite) intentionally fail the ANTLR parse and
     * fall through to delegation.
     */
    private val manifest = listOf(
        // WITH / CTE queries (SQL_WITH_QUERY_EXPRESSION / SQL_WITH_CLAUSE / SQL_NAMED_QUERY_DEFINITION),
        // including a qualified `db.tbl` table ref (SQL_REFERENCE qualifier) and RECURSIVE.
        "mysql-core/01-select-with-lag-toplevel",
        "mysql-core/02-watch-time-lag-query",
        "mysql-core/03-scalar-subquery-interval",
        "mysql-core/29-with-recursive",
        // Baselines: SELECT + WHERE, JOIN, self-join; expression precedence; query-tail clauses.
        "mysql-core/04-baseline-select",
        "mysql-core/05-baseline-join",
        "mysql-core/23-self-join",
        "mysql-core/07-operator-precedence-chain",
        "mysql-core/30-limit-offset-variants",
        "mysql-core/31-distinct",
        // GROUP BY / HAVING with COUNT(*) — the delegation unlock (function-call INFO frames).
        "mysql-core/06-baseline-group-by",
        "mysql-core/32-having",
        // Star projection + chained multi-type joins (nested SQL_JOIN_EXPRESSION); JOIN ... USING.
        "mysql-core/21-joins-inner-left-right-cross",
        "mysql-core/22-join-using",
        // Delegated expression families: CASE, BETWEEN/IN/LIKE/IS NULL, INTERVAL, literal zoo.
        "mysql-core/08-case-simple",
        "mysql-core/09-case-searched",
        "mysql-core/10-between-in-like-isnull",
        "mysql-core/19-literal-zoo",
        "mysql-core/20-interval-arithmetic",
        // Builtin-function calls delegated: TRIM, EXTRACT, POSITION, GROUP_CONCAT, COUNT(DISTINCT ...).
        "mysql-core/14-trim-leading",
        "mysql-core/15-extract-year",
        "mysql-core/16-position-in",
        "mysql-core/17-group-concat",
        "mysql-core/18-count-distinct-multi",
        // Subqueries (scalar / EXISTS / correlated) and a derived table (SQL_PARENTHESIZED_QUERY_EXPR).
        "mysql-core/11-scalar-subquery",
        "mysql-core/12-exists-subquery",
        "mysql-core/13-correlated-subquery",
        "mysql-core/24-derived-table",
        // UNION: flat SQL_UNION_EXPRESSION, and parenthesised branches with union-level ORDER BY/LIMIT.
        "mysql-core/25-union-vs-union-all",
        "mysql-core/26-parenthesized-union-order-limit",
        // Window functions (OVER frame + named WINDOW clause) — analytic subtree delegated.
        "mysql-core/27-window-frame-rows",
        "mysql-core/28-named-window-clause",
        // Statement long-tail (Part 2): DELETE ... USING replays byte-identical (DeleteContext +
        // synthetic SQL_DELETE_DML_INSTRUCTION / SQL_CLAUSE / SQL_FROM_CLAUSE; relations reuse the
        // query join machinery). UPDATE ... JOIN ... SET (MySQL's multi-table form) is NOT in the
        // Doris grammar, so it lands here via the honest ANTLR-reject bail -> delegation — pinned to
        // prove the new UPDATE lead can never make it worse. The single-table UPDATE that DOES replay
        // is pinned by the lenient-parity manifest below (doris/51 flag-on == typed flag-off golden).
        "mysql-core/33-update-with-join",
        "mysql-core/34-delete-using",
    )

    /**
     * ## Route B unparked — Doris STATEMENT replay manifest
     *
     * Beyond the query family (above), the replayer now types Doris STATEMENT leads: CREATE [MATERIALIZED]
     * VIEW, Doris CREATE TABLE, REFRESH / WARM UP / SWITCH, and QUALIFY queries. These have NO byte-for-byte
     * MySQL golden (MySQL either can't parse them or shapes them differently), so unlike the query manifest
     * they are pinned against their OWN recorded trees under golden/replay/doris/ (record mode:
     * -Pgolden.record=true), independent of the delegation goldens. Each case additionally asserts the
     * honesty invariants: ZERO PsiErrorElements, the expected top-level statement element type(s), and
     * boundary preservation (statement count) — the same contract DorisRegressionTest enforces flag-off.
     *
     * Statement families NEWLY typed by replay here (vs. the structureless lenient blob flag-off):
     *  - 12 / 13: CREATE [OR REPLACE] VIEW  -> SQL_CREATE_VIEW_STATEMENT (name + AS-query replayed in full,
     *    including a lateral-view FROM and the masked `* EXCEPT(...)` body).
     *  - 15: REFRESH (+table ref), WARM UP (+table refs), CREATE DATABASE (still lenient), Doris CREATE TABLE
     *    -> SQL_CREATE_TABLE_STATEMENT (column definitions + DISTRIBUTED reference-list; Doris clauses = runs).
     *  - 16: QUALIFY query -> SQL_SELECT_STATEMENT with a real SQL_QUALIFY_CLAUSE (delegated window expr).
     *  - 19: SWITCH catalog -> SQL_STATEMENT + catalog identifier.
     *  - 21 / 22: multi-statement boundary preservation across replayed + delegated statements.
     */
    /**
     * [contains]: node names that MUST appear somewhere in the replayed tree — used where the top-level
     * type alone cannot distinguish a successful replay from the lenient fallback (both SQL_STATEMENT),
     * so a silent fallback cannot be re-recorded as a passing golden.
     */
    private data class StmtCase(
        val rel: String,
        val statements: Int,
        val tops: List<String>,
        val contains: List<String> = emptyList(),
    )

    private val dorisStatements = listOf(
        StmtCase("doris/12-create-view-modern-body", 1, listOf("SQL_CREATE_VIEW_STATEMENT")),
        StmtCase("doris/13-create-view-lateral-view", 1, listOf("SQL_CREATE_VIEW_STATEMENT")),
        StmtCase(
            "doris/15-doris-admin-statements", 4,
            listOf("SQL_STATEMENT", "SQL_STATEMENT", "SQL_STATEMENT", "SQL_CREATE_TABLE_STATEMENT"),
        ),
        // CREATE JOB: the job wrapper stays SQL_STATEMENT (header = token run) but the DO-body INSERT is
        // REAL nested insert PSI — SQL_INSERT_STATEMENT > SQL_INSERT_DML_INSTRUCTION > SQL_TABLE_COLUMN_LIST
        // > SQL_TABLE_REFERENCE + the replayed query (shape mirrors the platform's own MySQL CREATE EVENT
        // ... DO INSERT; pinned by the golden). Top-level type list only sees the outer SQL_STATEMENT.
        StmtCase(
            "doris/14-create-job", 1, listOf("SQL_STATEMENT"),
            contains = listOf("SQL_INSERT_STATEMENT", "SQL_INSERT_DML_INSTRUCTION", "SQL_TABLE_COLUMN_LIST"),
        ),
        StmtCase("doris/16-qualify-clause", 1, listOf("SQL_SELECT_STATEMENT")),
        StmtCase("doris/19-switch-catalog", 2, listOf("SQL_STATEMENT", "SQL_SELECT_STATEMENT")),
        StmtCase(
            "doris/21-five-statement-boundaries", 5,
            listOf(
                "SQL_CREATE_VIEW_STATEMENT", "SQL_SELECT_STATEMENT", "SQL_CREATE_VIEW_STATEMENT",
                "SQL_INSERT_STATEMENT", "SQL_SELECT_STATEMENT",
            ),
        ),
        StmtCase(
            "doris/22-lenient-statement-no-eat-next", 4,
            listOf("SQL_STATEMENT", "SQL_SELECT_STATEMENT", "SQL_STATEMENT", "SQL_SELECT_STATEMENT"),
        ),
        // DDL clause-internals typing (Task 1): CREATE MATERIALIZED VIEW with a column list (each a real
        // SQL_COLUMN_DEFINITION), a PARTITION BY (date_trunc(...)) whose key delegates to a real
        // SQL_FUNCTION_CALL, DUPLICATE KEY / DISTRIBUTED BY HASH reference-lists, PROPERTIES, and an
        // AS-query. `contains` pins the delegated function call + typed column def so a silent fallback to
        // loose tokens can't be re-recorded as passing.
        StmtCase(
            "doris/23-create-mtmv-partition-expr", 1, listOf("SQL_CREATE_VIEW_STATEMENT"),
            contains = listOf("SQL_COLUMN_DEFINITION", "SQL_FUNCTION_CALL", "SQL_REFERENCE_LIST"),
        ),
        // CREATE TABLE with inline `INDEX ... USING INVERTED` (SQL_INDEX_DEFINITION > SQL_INDEX_REFERENCE),
        // AUTO PARTITION BY RANGE(date_trunc(...)) delegated to SQL_FUNCTION_CALL, DISTRIBUTED ... BUCKETS,
        // PROPERTIES. `contains` pins the index definition + reference + delegated partition call.
        StmtCase(
            "doris/24-create-table-inverted-index-auto-partition", 1, listOf("SQL_CREATE_TABLE_STATEMENT"),
            contains = listOf("SQL_INDEX_DEFINITION", "SQL_INDEX_REFERENCE", "SQL_FUNCTION_CALL"),
        ),
        // REFRESH MATERIALIZED VIEW <name> COMPLETE (Task 2): typed SQL_STATEMENT lead with a navigable
        // SQL_TABLE_REFERENCE for the MV name (mirrors REFRESH TABLE). Was loose tokens with no wrapper.
        StmtCase(
            "doris/25-refresh-materialized-view-complete", 1, listOf("SQL_STATEMENT"),
            contains = listOf("SQL_TABLE_REFERENCE"),
        ),
        // REFRESH MATERIALIZED VIEW ... PARTITION(p) / PARTITIONS(p_a, p_b) / AUTO (dogfood find):
        // the partition-spec/AUTO tails must not break the typed statement lead — same SQL_STATEMENT +
        // navigable SQL_TABLE_REFERENCE as the COMPLETE form, tails as token runs.
        StmtCase(
            "doris/26-refresh-mv-partition", 3,
            listOf("SQL_STATEMENT", "SQL_STATEMENT", "SQL_STATEMENT"),
            contains = listOf("SQL_TABLE_REFERENCE"),
        ),
        // --- Phase 2 harvest: CREATE TABLE model variants. All type SQL_CREATE_TABLE_STATEMENT with
        // real SQL_COLUMN_DEFINITIONs; Doris model clauses (UNIQUE/AGGREGATE KEY, DISTRIBUTED BY)
        // carry SQL_REFERENCE_LIST key columns that resolve against the sibling definitions; property
        // bags stay token runs. `contains` pins the typed shape so a lenient fallback can't re-record.
        StmtCase(
            "doris/28-create-table-unique-key-sequence", 1, listOf("SQL_CREATE_TABLE_STATEMENT"),
            contains = listOf("SQL_COLUMN_DEFINITION", "SQL_REFERENCE_LIST"),
        ),
        // Aggregate model: per-column agg types (SUM/MAX/MIN/REPLACE/BITMAP_UNION/HLL_UNION) stay
        // token runs INSIDE each typed SQL_COLUMN_DEFINITION.
        StmtCase(
            "doris/29-create-table-aggregate-key", 1, listOf("SQL_CREATE_TABLE_STATEMENT"),
            contains = listOf("SQL_COLUMN_DEFINITION", "SQL_REFERENCE_LIST"),
        ),
        StmtCase(
            "doris/30-create-table-colocate-bloom", 1, listOf("SQL_CREATE_TABLE_STATEMENT"),
            contains = listOf("SQL_COLUMN_DEFINITION", "SQL_REFERENCE_LIST"),
        ),
        // PARTITION BY RANGE(col) with a BARE column key: the identityOrFunction delegation renders a
        // SQL_COLUMN_REFERENCE that resolves against the statement's own column definitions.
        StmtCase(
            "doris/31-create-table-dynamic-partition", 1, listOf("SQL_CREATE_TABLE_STATEMENT"),
            contains = listOf("SQL_COLUMN_DEFINITION", "SQL_COLUMN_REFERENCE"),
        ),
        // LIST partitioning: per-partition VALUES IN ((...)) tuples stay token runs inside the typed
        // statement; the LIST(region, city) keys delegate as column references.
        StmtCase(
            "doris/32-create-table-list-partition", 1, listOf("SQL_CREATE_TABLE_STATEMENT"),
            contains = listOf("SQL_COLUMN_DEFINITION", "SQL_COLUMN_REFERENCE"),
        ),
        // Multi-column RANGE with the half-open interval form (`VALUES [(...), (...))`) — the bracket
        // tokens replay as runs; boundary and zero-error invariants are the point.
        StmtCase(
            "doris/33-create-table-range-multi-column", 1, listOf("SQL_CREATE_TABLE_STATEMENT"),
            contains = listOf("SQL_COLUMN_DEFINITION"),
        ),
        // Generated column (`c INT AS (a + b)`): the expression materialises through the CST mappings
        // (SQL_BINARY_EXPRESSION over column references) inside the typed column definition.
        StmtCase(
            "doris/36-create-table-generated-column", 1, listOf("SQL_CREATE_TABLE_STATEMENT"),
            contains = listOf("SQL_COLUMN_DEFINITION"),
        ),
        // CREATE MATERIALIZED VIEW refresh strategies: BUILD IMMEDIATE/DEFERRED, REFRESH COMPLETE/AUTO,
        // ON SCHEDULE EVERY ... STARTS / ON COMMIT — all header token runs inside the typed
        // SQL_CREATE_VIEW_STATEMENT; the AS-query replays in full under SQL_AS_QUERY_CLAUSE.
        StmtCase(
            "doris/40-create-mtmv-refresh-strategies", 2,
            listOf("SQL_CREATE_VIEW_STATEMENT", "SQL_CREATE_VIEW_STATEMENT"),
            contains = listOf("SQL_AS_QUERY_CLAUSE"),
        ),
        // MTMV with a BARE-COLUMN `PARTITION BY (event_date)` + workload_group property: the partition
        // column deliberately does NOT delegate (no resolvable scope -> would red-flag); it stays an
        // identifier leaf. The function form (corpus 23) keeps its delegated SQL_FUNCTION_CALL.
        StmtCase(
            "doris/41-create-mtmv-partition-column-workload", 1, listOf("SQL_CREATE_VIEW_STATEMENT"),
            contains = listOf("SQL_AS_QUERY_CLAUSE"),
        ),
        // CREATE JOB schedule variants (EVERY n MINUTE / EVERY n DAY STARTS..ENDS / one-time AT): job
        // wrapper stays SQL_STATEMENT, each DO-body INSERT gets the real insert skeleton (corpus 14 shape).
        StmtCase(
            "doris/42-create-job-schedule-variants", 3,
            listOf("SQL_STATEMENT", "SQL_STATEMENT", "SQL_STATEMENT"),
            contains = listOf("SQL_INSERT_STATEMENT", "SQL_INSERT_DML_INSTRUCTION"),
        ),
    )

    /**
     * Lenient-parity manifest: corpus files whose flag-ON tree must equal the recorded flag-off
     * (delegation/lenient) golden BYTE-FOR-BYTE — either because the replayer DECLINES them
     * (CREATE TABLE LIKE: grammar-accepted but unmapped statement lead; CTAS: reference lists with no
     * column definitions to resolve against) or because their leads never route to replay at all
     * (ALTER / EXPORT / BACKUP / GRANT / CREATE USER / CATALOG / RESOURCE / ROUTINE LOAD / DML).
     * Guards the "accepted-but-noisy" failure mode: if replay ever starts consuming one of these and
     * reshapes it, this diff screams before a user sees it.
     */
    private val lenientParity = listOf(
        "doris/34-create-table-like",
        "doris/35-ctas-variants",
        "doris/37-alter-table-columns",
        "doris/38-alter-table-partitions",
        "doris/39-alter-table-rename-properties-rollup",
        "doris/43-create-routine-load-kafka",
        "doris/44-export-table",
        "doris/45-backup-restore",
        "doris/46-grant-revoke",
        "doris/47-create-user-role",
        "doris/48-create-catalog-property-bags",
        "doris/49-create-resource-workload-group",
        "doris/50-delete-from-partition",
        "doris/51-update-set",
        "doris/52-truncate-partition",
        "doris/53-insert-variants",
    )

    /** Flag-ON must not reshape statements replay declines / never attempts (see [lenientParity]). */
    fun testLenientParityFamiliesUnchangedByReplayFlag() {
        val mismatches = StringBuilder()
        for (rel in lenientParity) {
            val sql = norm(corpusFile("$rel.sql").readText())
            val expected = norm(goldenFile("doris/$rel.tree").readText())
            if (expected != tree(dorisLang(), sql)) {
                mismatches.append("  - $rel: flag-on tree diverged from the flag-off golden\n")
            }
        }
        if (mismatches.isNotEmpty()) {
            fail("Replay flag reshaped statements it must decline/skip:\n$mismatches")
        }
    }

    private val recordMode: Boolean get() = System.getProperty("golden.record") == "true"

    /**
     * The Doris statement-replay contract. Record mode writes each replayed tree to golden/replay/<rel>.tree;
     * verify mode diffs against it AND checks the zero-error / top-type / statement-count invariants.
     */
    fun testDorisStatementReplayShapes() {
        val mismatches = StringBuilder()
        for (case in dorisStatements) {
            val sql = norm(corpusFile("${case.rel}.sql").readText())
            val actual = tree(dorisLang(), sql)

            val errors = Regex("PsiErrorElement").findAll(actual).count()
            if (errors != 0) mismatches.append("  - ${case.rel}: $errors PsiErrorElement(s) under replay\n")

            val tops = Regex("^  (SQL_[A-Z_]+)", RegexOption.MULTILINE).findAll(actual).map { it.groupValues[1] }.toList()
            val stmtCount = tops.count { it.endsWith("STATEMENT") }
            if (stmtCount != case.statements) {
                mismatches.append("  - ${case.rel}: statement count ${stmtCount} != expected ${case.statements}\n")
            }
            val expectedTops = case.tops
            if (tops != expectedTops) {
                mismatches.append("  - ${case.rel}: top-level nodes $tops != expected $expectedTops\n")
            }
            for (needle in case.contains) {
                if (!actual.contains(needle)) {
                    mismatches.append("  - ${case.rel}: expected node $needle absent (replay fell back?)\n")
                }
            }

            val golden = replayGoldenFile("${case.rel}.tree")
            if (recordMode) {
                golden.parentFile.mkdirs()
                golden.writeText(actual)
            } else if (!golden.exists()) {
                mismatches.append("  - ${case.rel}: MISSING replay golden ${golden.path}\n")
            } else if (norm(golden.readText()) != actual) {
                mismatches.append("  - ${case.rel}: replayed tree differs from golden/replay/${case.rel}.tree\n")
            }
        }
        if (mismatches.isNotEmpty()) {
            fail(
                "Doris statement-replay contract failed:\n$mismatches" +
                    "Re-record with -Pgolden.record=true after reviewing (git diff src/test/resources/golden/replay)."
            )
        }
    }

    /** Headline Gate-2 criterion: the entire manifest replays byte-identical to the MySQL goldens. */
    fun testManifestIsByteIdenticalToMysqlGoldens() {
        val mismatches = StringBuilder()
        for (rel in manifest) {
            val sql = norm(corpusFile("$rel.sql").readText())
            val expected = norm(goldenFile("mysql/$rel.tree").readText())
            val actual = tree(dorisLang(), sql)
            if (expected != actual) mismatches.append("  - $rel: replayed tree differs from MySQL golden\n")
        }
        if (mismatches.isNotEmpty()) {
            fail("Route B replay diverged from the MySQL golden for:\n$mismatches")
        }
    }

    /**
     * TVF-in-FROM bail-out (dogfood 2026-07-08, item 9): a query whose FROM holds a table-valued
     * function must NOT be replayed — the replayer has no mapping for the call and would flatten it
     * to a bare identifier, losing the SqlFunctionCallExpression the whole TVF stack (builtin
     * overlay, DorisTypeSystem static schemas, property-bag suppression) keys on. With the flag ON
     * the tree must therefore equal the recorded flag-off (delegation) golden byte-for-byte.
     */
    fun testTvfQueryBailsToDelegation() {
        val rel = "doris/17-table-function"
        val sql = norm(corpusFile("$rel.sql").readText())
        val expected = norm(goldenFile("doris/$rel.tree").readText())
        val actual = tree(dorisLang(), sql)
        assertEquals("TVF query must bail replay and match the delegation golden", expected, actual)
        assertTrue("TVF call must stay a real function call", actual.contains("SQL_FUNCTION_CALL"))
    }

    /**
     * Sanity guard: with the flag OFF the DorisSQL dialect must produce its normal tree (which for a
     * plain SELECT already equals the MySQL golden via delegation). Proves the replay path is the
     * thing under test, not delegation.
     */
    fun testFlagOffStillMatchesGolden() {
        System.setProperty(FLAG, "false") // 0.5.0: unset now means ON; explicit false = flag-off
        try {
            val rel = "mysql-core/04-baseline-select"
            val sql = norm(corpusFile("$rel.sql").readText())
            val expected = norm(goldenFile("mysql/$rel.tree").readText())
            assertEquals("Delegation (flag off) must match the MySQL golden for $rel", expected, tree(dorisLang(), sql))
        } finally {
            System.setProperty(FLAG, "true")
        }
    }

    private fun tree(lang: Language, sql: String): String {
        // "corpus.sql" so the SqlFile root label matches the recorded goldens.
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("corpus.sql", lang, sql, false, true)
        assertNotNull("createFileFromText returned null for language '${lang.id}'", file)
        return norm(DebugUtil.psiToString(file!!, true))
    }

    private fun dorisLang(): Language =
        Language.findLanguageByID("DorisSQL")
            ?: error("DorisSQL language must be registered in the test environment")

    private fun corpusFile(rel: String): File =
        File(System.getProperty("corpus.dir") ?: error("corpus.dir system property not set"), rel)

    private fun goldenFile(rel: String): File =
        File(System.getProperty("golden.dir") ?: error("golden.dir system property not set"), rel)

    /** Replay-shape golden for a Doris statement case: golden/replay/<rel>.tree (record-mode owned). */
    private fun replayGoldenFile(rel: String): File =
        File(System.getProperty("golden.dir") ?: error("golden.dir system property not set"), "replay/$rel")

    private fun norm(s: String): String = s.replace("\r\n", "\n").replace("\r", "\n")

    private companion object {
        const val FLAG = "doris.replay.poc"
    }
}
