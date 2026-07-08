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
            System.clearProperty(FLAG)
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
     * NOT in scope for the replayer (it only fires for a SELECT/WITH/parenthesised query lead): the
     * DML/DDL statements (mysql-core 33–44: UPDATE/DELETE/INSERT/REPLACE/CREATE/ALTER/TRUNCATE/EXPLAIN/
     * SET/transaction). Those keep their existing delegation/lenient handling; add a query-vs-statement
     * dispatcher before extending replay to them. Error-recovery corpora (the edge suite) intentionally
     * fail the ANTLR parse and fall through to delegation.
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
    )

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
     * Sanity guard: with the flag OFF the DorisSQL dialect must produce its normal tree (which for a
     * plain SELECT already equals the MySQL golden via delegation). Proves the replay path is the
     * thing under test, not delegation.
     */
    fun testFlagOffStillMatchesGolden() {
        System.clearProperty(FLAG)
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
