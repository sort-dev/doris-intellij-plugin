package dev.sort.doris.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
        for (expected in listOf("DATE_TRUNC(&lt;datetime&gt;, &lt;time_unit&gt;)", "DATE_TRUNC(&lt;time_unit&gt;, &lt;datetime&gt;)",
            "time_unit", "quarter", "2010-01-01", "doris.apache.org", "参数")) {
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
        assertTrue("Hover target must provide DATE_TRUNC documentation", docs.any { it.contains("DATE_TRUNC(&lt;datetime&gt;, &lt;time_unit&gt;)") })
    }

    fun testFunctionArgumentNamedLikeBuiltinDoesNotReceiveFunctionDocs() {
        val html = documentation("SELECT coalesce(date_tr<caret>unc, NULL) FROM t;")
        assertFalse(html.orEmpty(), html.orEmpty().contains("DATE_TRUNC(&lt;datetime&gt;, &lt;time_unit&gt;)"))
    }

    fun testBundledChineseResourcesAreReadableAndVersioned() {
        assertTrue("The complete official index must be bundled", DorisChineseFunctionDocs.names().size >= 789)
        val pages = DorisChineseFunctionDocs.names().map { DorisChineseFunctionDocs.page(it)!! }.toSet()
        assertEquals(670, pages.size)
        assertNull("Example DDL must not become a function alias", DorisChineseFunctionDocs.page("PROPERTIES"))
        for (page in pages) {
            val html = DorisChineseFunctionDocs.html(page)
            assertTrue("Missing Chinese content for $page", Regex("[\\u4e00-\\u9fff]").containsMatchIn(html))
            assertTrue("Missing offline source attribution for $page", html.contains("官方中文文档"))
            assertFalse("Network images must not be loaded: $page", html.contains("<img", true))
            assertFalse("Executable HTML must not be bundled: $page", html.contains("<script", true))
            assertTrue(DorisChineseFunctionDocs.url(page).startsWith("https://doris.apache.org/zh-CN/docs/4.x/"))
        }
        val covered = dev.brikk.house.sql.metadata.DORIS_METADATA.default.functions.functions.count {
            DorisChineseFunctionDocs.page(it.name) != null
        }
        assertEquals("Coverage report must match the actual runtime catalog", 641, covered)
    }

    fun testChineseDocsAcrossFunctionFamilies() {
        val cases = listOf(
            "SELECT DATE_FOR<caret>MAT('2026-01-15', '%Y-%m');" to "格式",
            "SELECT CON<caret>CAT('a', 'b');" to "字符串",
            "SELECT ARRAY_M<caret>AP(x -> x + 1, [1, 2]);" to "参数",
            "SELECT MAP_K<caret>EYS(map('a', 1));" to "返回",
            "SELECT JSON_PA<caret>RSE('{}');" to "解析",
            "SELECT BITMAP_CO<caret>UNT(BITMAP_EMPTY());" to "参数",
            "SELECT HLL_H<caret>ASH('a');" to "返回",
            "SELECT S<caret>UM(x) FROM t;" to "参数",
            "SELECT ROW_NU<caret>MBER() OVER ();" to "窗口",
            "SELECT EXPLO<caret>DE([1, 2]);" to "参数",
            "SELECT * FROM S<caret>3('uri'='s3://example/data.csv', 'format'='csv');" to "参数",
        )
        for ((sql, expected) in cases) {
            val doc = documentation(sql)
            assertNotNull("Missing docs: $sql", doc)
            assertTrue("Missing Chinese $expected for $sql: $doc", doc!!.contains(expected))
            assertTrue("Missing official source for $sql", doc.contains("官方中文文档"))
        }
    }

    fun testDocumentedAliasesHaveFullChineseDocs() {
        val doc = documentation("SELECT PO<caret>WER(2, 3);")
        assertNotNull(doc)
        assertTrue(doc, doc!!.contains("次方"))
        assertTrue(doc, doc.contains("示例"))
    }

    fun testSpecialGrammarFunctionDocumentation() {
        for (sql in listOf(
            "SELECT EXTR<caret>ACT(YEAR FROM '2026-01-15');",
            "SELECT DATE_A<caret>DD('2026-01-15', INTERVAL 1 DAY);",
            "SELECT CA<caret>ST(1 AS STRING);",
        )) {
            val doc = documentation(sql)
            assertNotNull("Missing docs: $sql", doc)
            assertTrue("Missing Chinese syntax for $sql", doc!!.contains("语法"))
        }
    }

    fun testTableDeclarationDoesNotReceiveFunctionDocs() {
        val doc = documentation("CREATE TABLE A<caret>BS (id INT);")
        assertFalse(doc.orEmpty(), doc.orEmpty().contains("官方中文文档"))
    }

    fun testInternalMetadataOnlyFunctionHasHonestChineseFallback() {
        val doc = documentation("SELECT CRC32_IN<caret>TERNAL('a');")
        assertNotNull(doc)
        assertTrue(doc, doc!!.contains("未收录"))
        assertTrue(doc, doc.contains("参数"))
        assertFalse(doc, doc.contains("官方中文文档</a>"))
    }

    fun testOtherBuiltinHasMetadataSignature() {
        val html = documentation("SELECT BITMAP_CO<caret>UNT(BITMAP_EMPTY());")
        assertNotNull(html)
        assertTrue(html, html!!.contains("BITMAP_COUNT("))
    }

    fun testColumnNamedDateTruncDoesNotReceiveFunctionDocs() {
        val html = documentation("SELECT date_tr<caret>unc FROM t;")
        assertFalse(html.orEmpty(), html.orEmpty().contains("DATE_TRUNC(&lt;datetime&gt;, &lt;time_unit&gt;)"))
    }

    fun testQualifiedUdfDoesNotReceiveBuiltinDocs() {
        val html = documentation("SELECT my_db.DATE_TR<caret>UNC(1);")
        assertFalse(html.orEmpty(), html.orEmpty().contains("DATE_TRUNC(&lt;datetime&gt;, &lt;time_unit&gt;)"))
    }

    fun testMisspelledFunctionDoesNotReceiveBuiltinDocs() {
        val html = documentation("SELECT DATE_TR<caret>UNC_ffo(1);")
        assertFalse(html.orEmpty(), html.orEmpty().contains("DATE_TRUNC(&lt;datetime&gt;, &lt;time_unit&gt;)"))
    }

    fun testEngineDocumentationContract() {
        val doc = documentation("CRE<caret>ATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO t SELECT 1")
        assertNotNull(doc)
        assertTrue(doc, doc!!.contains("doris.apache.org"))
    }

    fun testPropertyKeyDocumentationContract() {
        val doc = documentation("CREATE TABLE t (k INT) PROPERTIES (\"replication_n<caret>um\" = \"1\")")
        assertNotNull(doc)
        assertTrue(doc, doc!!.contains("doris.apache.org"))
    }
}
