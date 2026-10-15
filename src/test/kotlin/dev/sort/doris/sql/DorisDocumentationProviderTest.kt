package dev.sort.doris.sql

import com.intellij.lang.LanguageDocumentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping

/** Quick Documentation reaches the Doris pages for statements, and leaves table references to the platform. */
class DorisDocumentationProviderTest : BasePlatformTestCase() {
    private fun provider(): DorisDocumentationProvider {
        val providers = LanguageDocumentation.INSTANCE.allForLanguage(DorisSqlDialect.INSTANCE)
        return providers.filterIsInstance<DorisDocumentationProvider>().single()
    }

    private fun documentedAt(sql: String) = run {
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        val file = myFixture.configureByText("docs.sql", sql)
        val offset = myFixture.caretOffset
        provider().getCustomDocumentationElement(myFixture.editor, file, file.findElementAt(offset), offset)
    }

    fun testCreateJobLinksTheDorisPage() {
        val element = checkNotNull(documentedAt("CRE<caret>ATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO t SELECT 1"))
        val url = provider().getUrlFor(element, null).orEmpty().single()
        assertTrue(url, url.startsWith("https://doris.apache.org/") && "job" in url.lowercase())
        assertTrue(provider().generateDoc(element, null).orEmpty().contains(url))
    }

    fun testATableReferenceKeepsThePlatformElement() {
        assertNull(documentedAt("SELECT * FROM ord<caret>ers"))
    }
}
