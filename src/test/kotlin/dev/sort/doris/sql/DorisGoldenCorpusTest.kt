package dev.sort.doris.sql

import com.intellij.lang.Language
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.DebugUtil
import com.intellij.sql.dialects.mysql.MysqlDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Dual golden-tree corpus (Gate 1 of RESEARCH-when-hell-freezes-over-parser.md, "The golden-corpus
 * method"). For every SQL construct file under src/test/resources/corpus we pin TWO PSI trees:
 *
 *  - golden/doris/<name>.tree — the tree our DorisSQL dialect produces (the shape our plugin ships).
 *  - golden/mysql/<name>.tree — the tree the RAW platform MySQL dialect produces for the same SQL.
 *
 * The MySQL goldens are an upstream-drift alarm: when a future DataGrip version reshapes MySQL's
 * trees, [testMysqlGoldenTrees] fails loudly, telling us exactly which constructs moved so we can
 * re-audit our delegation before shipping against the new platform. The Doris and MySQL trees WILL
 * differ for Doris-only constructs (INSERT OVERWRITE, EXCEPT, CREATE JOB, ...) — that divergence is
 * the point; both are recorded without judgment.
 *
 * Modes (driven by system properties wired in build.gradle.kts):
 *  - RECORD  (-Pgolden.record=true): (re)write every golden file, then pass.
 *  - VERIFY  (default): assert each corpus file's tree equals its golden for each dialect; a missing
 *    golden is a failure. All mismatches are aggregated into a single failure listing every file, so
 *    the JUnit3-style method count stays at two (one per dialect).
 */
class DorisGoldenCorpusTest : BasePlatformTestCase() {

    private val corpusDir: File
        get() = File(System.getProperty("corpus.dir") ?: error("corpus.dir system property not set"))

    private val goldenDir: File
        get() = File(System.getProperty("golden.dir") ?: error("golden.dir system property not set"))

    private val recordMode: Boolean
        get() = System.getProperty("golden.record") == "true"

    /** Deterministic, sorted iteration so goldens and diffs are stable across machines. */
    private fun corpusFiles(): List<File> =
        (corpusDir.listFiles { f -> f.isFile && f.name.endsWith(".sql") } ?: emptyArray())
            .sortedBy { it.name }

    /** Trees are pure text; normalize line endings so goldens compare byte-stable across platforms. */
    private fun norm(s: String): String = s.replace("\r\n", "\n").replace("\r", "\n")

    private fun tree(lang: Language, sql: String): String {
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("corpus.sql", lang, sql, false, true)
        assertNotNull("createFileFromText returned null for language '${lang.id}'", file)
        return norm(DebugUtil.psiToString(file!!, true))
    }

    private fun dorisLang(): Language =
        Language.findLanguageByID("DorisSQL")
            ?: error("DorisSQL language must be registered in the test environment")

    /**
     * MysqlDialect.INSTANCE extends MysqlDialectBase -> SqlDialect -> ... -> SqlLanguageDialectBase,
     * which IS a com.intellij.lang.Language, so it can be handed straight to createFileFromText.
     * Verified working in this light fixture (the com.intellij.database plugin is loaded via
     * idea.load.plugins.id in build.gradle.kts). Language.findLanguageByID("MySQL") resolves to the
     * same instance, but the direct INSTANCE reference is compile-time-checked and unambiguous.
     */
    private fun mysqlLang(): Language = MysqlDialect.INSTANCE

    private fun runDialect(dialectName: String, lang: Language) {
        val files = corpusFiles()
        assertTrue("no corpus *.sql files found in $corpusDir", files.isNotEmpty())
        val outDir = File(goldenDir, dialectName)

        if (recordMode) {
            outDir.mkdirs()
            for (f in files) {
                val actual = tree(lang, norm(f.readText()))
                File(outDir, f.nameWithoutExtension + ".tree").writeText(actual)
            }
            return
        }

        val mismatches = StringBuilder()
        for (f in files) {
            val actual = tree(lang, norm(f.readText()))
            val goldenFile = File(outDir, f.nameWithoutExtension + ".tree")
            if (!goldenFile.exists()) {
                mismatches.append("  - ${f.name} [$dialectName]: MISSING golden ${goldenFile.path}\n")
                continue
            }
            val golden = norm(goldenFile.readText())
            if (golden != actual) {
                mismatches.append("  - ${f.name} [$dialectName]: PSI tree differs from golden\n")
            }
        }

        if (mismatches.isNotEmpty()) {
            fail(
                "Golden PSI-tree mismatch for the '$dialectName' dialect:\n" +
                    mismatches +
                    "Re-record with -Pgolden.record=true after reviewing the diff " +
                    "(git diff src/test/resources/golden/$dialectName)."
            )
        }
    }

    fun testDorisGoldenTrees() = runDialect("doris", dorisLang())

    fun testMysqlGoldenTrees() = runDialect("mysql", mysqlLang())
}
