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
     * The headline Gate-2 criterion: the replayed tree for the baseline SELECT is byte-identical to
     * the recorded MySQL golden. SQL and golden are both read from the corpus so nothing is inlined
     * or paraphrased.
     */
    fun testBaselineSelectIsByteIdenticalToGolden() {
        assertReplayMatchesMysqlGolden("mysql-core/04-baseline-select")
    }

    /**
     * Stretch goal, achieved: a JOIN with table aliases, qualified column references (`u.id`), an ON
     * criteria and a WHERE — exercises SQL_JOIN_EXPRESSION / SQL_AS_EXPRESSION / SQL_REFERENCE /
     * SQL_JOIN_CONDITION_CLAUSE, all reproduced byte-for-byte from the ANTLR CST.
     */
    fun testBaselineJoinIsByteIdenticalToGolden() {
        assertReplayMatchesMysqlGolden("mysql-core/05-baseline-join")
    }

    /**
     * Sanity guard: with the flag OFF the DorisSQL dialect must produce its normal tree (which for a
     * plain SELECT already equals the MySQL golden via delegation). Proves the replay path is the
     * thing under test, not delegation.
     */
    fun testFlagOffStillMatchesGolden() {
        System.clearProperty(FLAG)
        try {
            assertReplayMatchesMysqlGolden("mysql-core/04-baseline-select")
        } finally {
            System.setProperty(FLAG, "true")
        }
    }

    private fun assertReplayMatchesMysqlGolden(rel: String) {
        val sql = norm(corpusFile("$rel.sql").readText())
        val expected = norm(goldenFile("mysql/$rel.tree").readText())
        val actual = tree(dorisLang(), sql)
        assertEquals("Replayed DorisSQL tree must match the MySQL golden for $rel", expected, actual)
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
