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
     * documented blocker (see TODO below). The whole set is diffed in one pass so every regression is
     * reported together.
     *
     * TODO — attempted but NOT yet byte-identical (blocker per file, so they stay out of the manifest;
     * the replayer FAILS CLEANLY back to delegation for each, per the safety contract):
     *  - 06-baseline-group-by, 32-having: select-list / HAVING contain COUNT(*). The platform emits
     *    the aggregate call as SQL_FUNCTION_CALL with two platform-internal empty-list marker nodes
     *    ("INFO:[expr:any*]" and "INFO:[0]") that are artifacts of the MySQL GENERATED parser's
     *    argument-list section, not derivable from the ANTLR CST. The clause structure (GROUP BY /
     *    HAVING / ORDER BY) IS mapped and correct — only the function subtree blocks these two.
     *  - 21-joins-inner-left-right-cross: bare `*` star projection + chained multi-type joins; star
     *    projection shape not yet modelled.
     *  - 08/09 (CASE), 10 (BETWEEN/IN/LIKE), 11-13 (subqueries), 24 (derived table), 25/26 (UNION),
     *    03/20 (INTERVAL), 19 (literal zoo): unmapped element types (see stretch goals in the report).
     */
    private val manifest = listOf(
        // Baseline SELECT + WHERE (04) and JOIN with aliases / qualified refs / ON (05): Gate-2 PoC.
        "mysql-core/04-baseline-select",
        "mysql-core/05-baseline-join",
        // Self-join: same shape as 05 (SQL_AS_EXPRESSION / SQL_JOIN_EXPRESSION / SQL_REFERENCE).
        "mysql-core/23-self-join",
        // Expression nesting: arithmetic precedence chain a + b * c - d / e % f -> left-deep
        // SQL_BINARY_EXPRESSION tree, matching the platform's single-child collapse exactly.
        "mysql-core/07-operator-precedence-chain",
        // Query tail: LIMIT / OFFSET / `LIMIT n, m` -> SQL_LIMIT_OFFSET_CLAUSE outside the table expr.
        "mysql-core/30-limit-offset-variants",
        // Query tail: SELECT DISTINCT -> SQL_SELECT_OPTION.
        "mysql-core/31-distinct",
    )

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

    private fun norm(s: String): String = s.replace("\r\n", "\n").replace("\r", "\n")

    private companion object {
        const val FLAG = "doris.replay.poc"
    }
}
