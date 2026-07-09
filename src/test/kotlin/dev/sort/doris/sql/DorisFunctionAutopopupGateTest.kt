package dev.sort.doris.sql

import com.intellij.psi.PsiElement
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThreeState

/**
 * Dogfood 2026-07-08 P1 (0.5.0): [DorisCompletionContributor]'s ~900-name function list was
 * contributed at every psi position and broke ordinary typing — `GROUP BY 1` offered `sha1`, `AS `
 * offered `asin`. The fix is an ALLOWLIST: functions autopopup only in positively-detected
 * expression positions ([DorisExpressionPosition]) and are otherwise explicit-invoke-only; a
 * digit-led prefix is skipped globally by [DorisFunctionAutoPopupConfidence].
 *
 * These tests pin the autopopup DECISION functions the platform consults (the confidence and the
 * position allowlist) plus the contributor's explicit-invoke and digit guards. Autopopup is a
 * decision the platform makes from these predicates, so asserting them directly is exact.
 */
class DorisFunctionAutopopupGateTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            SqlDialectMappings.getInstance(project).setMapping(null, null)
        } finally {
            super.tearDown()
        }
    }

    /** Configure DorisSQL text with a single `<caret>` and return the caret offset. */
    private fun configure(sql: String): Int {
        SqlDialectMappings.getInstance(project).setMapping(null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("c.sql", sql)
        return myFixture.caretOffset
    }

    private fun contextAt(offset: Int): PsiElement =
        myFixture.file.findElementAt((offset - 1).coerceAtLeast(0)) ?: myFixture.file

    private fun allowsFunctionAutopopup(sql: String): Boolean {
        val offset = configure(sql)
        return DorisExpressionPosition.isFunctionAutopopupPosition(myFixture.file, offset)
    }

    private fun skipAutopopup(sql: String): ThreeState {
        val offset = configure(sql)
        return DorisFunctionAutoPopupConfidence().shouldSkipAutopopup(contextAt(offset), myFixture.file, offset)
    }

    private fun explicitCompletions(sql: String): List<String> {
        configure(sql)
        return myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
    }

    // --- Criterion 1: GROUP BY 1 — digit-led prefix, no function autopopup, no sha1 even explicit -

    fun testDigitPrefixSkipsAutopopupGlobally() {
        assertEquals(
            "typing the digit in `GROUP BY 1` must skip the autopopup",
            ThreeState.YES,
            skipAutopopup("SELECT count(*) FROM t GROUP BY 1<caret>"),
        )
    }

    fun testDigitPrefixOffersNoFunctionsEvenOnExplicitInvoke() {
        val lookups = explicitCompletions("SELECT count(*) FROM t GROUP BY 1<caret>")
        assertFalse("`sha1` must not be offered for a digit prefix, got: ${lookups.take(25)}", lookups.contains("sha1"))
        assertFalse("`log10` must not be offered for a digit prefix", lookups.contains("log10"))
    }

    // --- Criterion 2/3: after AS / BY / FROM — not an expression position, no function autopopup ---

    fun testAfterAsKeywordIsNotAnAutopopupPosition() {
        assertFalse("`CREATE VIEW v AS ` + space must not autopopup functions", allowsFunctionAutopopup("CREATE VIEW v AS <caret>"))
        // ...and mid-typing the AS keyword itself (preceding token is the identifier `v`).
        assertFalse("typing `AS` must not autopopup functions", allowsFunctionAutopopup("CREATE VIEW v AS<caret>"))
    }

    fun testAfterGroupByAndFromAreNotAutopopupPositions() {
        assertFalse("after `GROUP BY ` no function autopopup", allowsFunctionAutopopup("SELECT a FROM t GROUP BY <caret>"))
        assertFalse("after `ORDER BY ` no function autopopup", allowsFunctionAutopopup("SELECT a FROM t ORDER BY <caret>"))
        assertFalse("after `FROM ` no function autopopup", allowsFunctionAutopopup("SELECT a FROM <caret>"))
        assertFalse("after a closing paren no function autopopup", allowsFunctionAutopopup("SELECT count(*)<caret>"))
    }

    // --- Criterion 4/5/6: real expression positions still autopopup functions -----------------------

    fun testExpressionPositionsAllowAutopopup() {
        assertTrue("after `SELECT ` is an expression position", allowsFunctionAutopopup("SELECT da<caret>"))
        assertTrue("after `=` is an expression position", allowsFunctionAutopopup("SELECT a FROM t WHERE x = ab<caret>"))
        assertTrue("after `,` in a select list is an expression position", allowsFunctionAutopopup("SELECT a, da<caret> FROM t"))
        assertTrue("inside a function call's parens is an expression position", allowsFunctionAutopopup("SELECT count(<caret>"))
        assertTrue("after a comma inside a call is an expression position", allowsFunctionAutopopup("SELECT date_trunc(x, <caret>"))
        assertTrue("after WHERE is an expression position", allowsFunctionAutopopup("SELECT a FROM t WHERE ab<caret>"))
    }

    fun testExpressionPositionNotSkippedByConfidence() {
        // An alphabetic prefix in an expression position stays UNSURE so autopopup can proceed.
        assertEquals(ThreeState.UNSURE, skipAutopopup("SELECT da<caret>"))
        assertEquals(ThreeState.UNSURE, skipAutopopup("SELECT a FROM t WHERE x = ab<caret>"))
    }

    // --- Criterion 4/7: explicit Ctrl+Space still offers the full function list --------------------

    fun testExplicitInvokeOffersFunctionsInExpressionPosition() {
        val lookups = explicitCompletions("SELECT da<caret>")
        assertTrue("`date_trunc` offered from prefix `da`, got: ${lookups.take(25)}", lookups.contains("date_trunc"))
    }

    fun testExplicitInvokeOffersFunctionsEvenAfterKeyword() {
        // Criterion 7: confidence/allowlist only gate AUTOPOPUP; explicit invoke gets functions
        // anywhere sensible — even right after AS, which is not an autopopup position.
        val lookups = explicitCompletions("CREATE VIEW v AS as<caret>")
        assertTrue("`asin` offered on explicit invoke after AS, got: ${lookups.take(25)}", lookups.contains("asin"))
    }
}
