package dev.sort.doris.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.DocumentationProviderContract

class DorisDocumentationProviderTest : BasePlatformTestCase() {
    private fun documentation(sql: String): String? {
        val file = myFixture.configureByText("function-docs.sql", sql)
        dev.brikk.house.sql.intellij.testing.setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        val original = myFixture.file.findElementAt(myFixture.caretOffset)
        val target = com.intellij.codeInsight.documentation.DocumentationManager.getInstance(project)
            .findTargetElement(myFixture.editor, myFixture.file) ?: return null
        return com.intellij.codeInsight.documentation.DocumentationManager.getProviderFromElement(target)
            .generateDoc(target, original)
    }

    fun testDateTruncDocumentationThroughIdeTarget() {
        val html = documentation("SELECT DATE_TR<caret>UNC('month', '2026-01-15');")
        assertNotNull("The IDE must find documentation for a Doris scalar function", html)
        for (expected in listOf("DATE_TRUNC(datetime, time_unit)", "DATE_TRUNC(time_unit, datetime)",
            "time_unit", "quarter", "2026-01-01", "doris.apache.org", "参数")) {
            assertTrue("Missing $expected in $html", html!!.contains(expected))
        }
    }

    fun testModernDocumentationTargetForLowercaseNestedCall() {
        val file = myFixture.configureByText("hover.sql", "SELECT coalesce(date_tr<caret>unc('2026-01-15', 'month'), NULL);")
        dev.brikk.house.sql.intellij.testing.setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        val targets = com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
        val docs = targets.mapNotNull {
            val target = (it as? com.intellij.lang.documentation.psi.PsiElementDocumentationTarget)?.targetElement
                ?: return@mapNotNull null
            com.intellij.codeInsight.documentation.DocumentationManager.getProviderFromElement(target).generateDoc(target, null)
        }
        assertTrue("Hover target must provide DATE_TRUNC documentation", docs.any { it.contains("DATE_TRUNC(datetime, time_unit)") })
    }

    fun testFunctionArgumentNamedLikeBuiltinDoesNotReceiveFunctionDocs() {
        val html = documentation("SELECT coalesce(date_tr<caret>unc, NULL) FROM t;")
        assertFalse(html.orEmpty(), html.orEmpty().contains("DATE_TRUNC(datetime, time_unit)"))
    }

    fun testOtherBuiltinHasMetadataSignature() {
        val html = documentation("SELECT BITMAP_CO<caret>UNT(BITMAP_EMPTY());")
        assertNotNull(html)
        assertTrue(html, html!!.contains("BITMAP_COUNT("))
    }

    fun testColumnNamedDateTruncDoesNotReceiveFunctionDocs() {
        val html = documentation("SELECT date_tr<caret>unc FROM t;")
        assertFalse(html.orEmpty(), html.orEmpty().contains("DATE_TRUNC(datetime, time_unit)"))
    }

    fun testQualifiedUdfDoesNotReceiveBuiltinDocs() {
        val html = documentation("SELECT my_db.DATE_TR<caret>UNC(1);")
        assertFalse(html.orEmpty(), html.orEmpty().contains("DATE_TRUNC(datetime, time_unit)"))
    }

    fun testMisspelledFunctionDoesNotReceiveBuiltinDocs() {
        val html = documentation("SELECT DATE_TR<caret>UNC_ffo(1);")
        assertFalse(html.orEmpty(), html.orEmpty().contains("DATE_TRUNC(datetime, time_unit)"))
    }

    fun testEngineDocumentationContract() {
        DocumentationProviderContract(myFixture, DorisSqlDialect.INSTANCE, testRootDisposable)
            .check("CRE<caret>ATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO t SELECT 1", "doris.apache.org", table = "orders")
    }

    fun testPropertyKeyDocumentationContract() {
        DocumentationProviderContract(myFixture, DorisSqlDialect.INSTANCE, testRootDisposable)
            .check("CREATE TABLE t (k INT) PROPERTIES (\"replication_n<caret>um\" = \"1\")", "doris.apache.org", table = "orders")
    }
}
