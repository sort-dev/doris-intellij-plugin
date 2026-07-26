package dev.sort.doris.sql

import com.intellij.lang.Language
import com.intellij.lang.LanguageCommenters
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * DorisSQL must use the platform's [com.intellij.codeInsight.generation.SelfManagingCommenter]
 * (`SqlNestedCommenter`, as ClickHouse/Redshift/DB2/… do), NOT a hand-rolled bare `Commenter`.
 * That is what inserts `-- ` WITH the trailing space (a bare `--` is not a valid Doris/MySQL line
 * comment and highlights as code) and makes Ctrl+/ over a selection comment every line and round-trip.
 */
class DorisCommenterWiringTest : BasePlatformTestCase() {

    fun testDorisUsesSqlNestedCommenter() {
        val lang = Language.findLanguageByID("DorisSQL")!!
        val commenter = LanguageCommenters.INSTANCE.forLanguage(lang)
        assertNotNull("no commenter registered for DorisSQL", commenter)
        assertEquals(
            "com.intellij.database.sql.common.core.psi.impl.support.SqlNestedCommenter",
            commenter!!.javaClass.name,
        )
    }

    /**
     * The line prefix now carries the REQUIRED trailing space (`"-- "`): a bare `--` is not a valid
     * Doris/MySQL line comment and highlights as code. This is the regression the hand-rolled bare
     * commenter caused (it returned `"--"`), and the whole reason for the swap.
     */
    fun testLinePrefixIncludesRequiredSpace() {
        val lang = Language.findLanguageByID("DorisSQL")!!
        val commenter = LanguageCommenters.INSTANCE.forLanguage(lang)!!
        assertEquals("-- ", commenter.lineCommentPrefix)
        assertEquals("/*", commenter.blockCommentPrefix)
        assertEquals("*/", commenter.blockCommentSuffix)
    }
}
