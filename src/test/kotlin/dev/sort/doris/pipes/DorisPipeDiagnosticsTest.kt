package dev.sort.doris.pipes

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.openapi.components.service
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.sql.DorisErrorAnnotator
import dev.sort.doris.sql.DorisHighlightInfoFilter
import dev.sort.doris.sql.DorisSqlDialect

class DorisPipeDiagnosticsTest : BasePlatformTestCase() {
    private val settings get() = project.service<DorisPipesSettings>()

    override fun setUp() {
        super.setUp()
        System.clearProperty(DorisPipes.PROPERTY)
        settings.enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            settings.execMarks.clear()
            settings.enabled = false
            System.clearProperty(DorisPipes.PROPERTY)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    fun testOnlyNativePipeOperatorsSuppressSemanticDiagnostics() {
        val ordinaryStatements = listOf(
            "SELECT missing, '|>' FROM (SELECT 1 AS x) t;",
            "SELECT missing /* |> */ FROM (SELECT 1 AS x) t;",
            "SELECT missing, \"|>\" FROM (SELECT 1 AS x) t;",
            "SELECT missing, `|>` FROM (SELECT 1 AS x) t;",
            "SELECT missing, \$\$|>\$\$ FROM (SELECT 1 AS x) t;",
            "SELECT missing | > 1 FROM (SELECT 1 AS x) t;",
            "SELECT missing |/* gap */> 1 FROM (SELECT 1 AS x) t;",
        )
        for ((index, sql) in ordinaryStatements.withIndex()) {
            val file = dorisFile("ordinary-$index.sql", sql)
            assertTrue(sql, DorisHighlightInfoFilter().accept(infoAt(file.text, "missing", "Unable to resolve column 'missing'"), file))
        }

        val pipe = dorisFile("pipe.sql", "FROM t |> SELECT missing;")
        assertFalse(
            DorisHighlightInfoFilter().accept(
                infoAt(pipe.text, "missing", "Unable to resolve column 'missing'"),
                pipe,
            ),
        )
    }

    fun testDorisOwnedPipeAndServerDiagnosticsAlwaysSurviveFiltering() {
        val file = dorisFile("server.sql", "FROM t |> SELECT missing;")
        val filter = DorisHighlightInfoFilter()
        assertTrue(filter.accept(infoAt(file.text, "missing", "Doris Pipes: invalid stage"), file))
        assertTrue(
            filter.accept(
                infoAt(file.text, "missing", "Doris (server): Unable to resolve object type at missing"),
                file,
            ),
        )

        val text = file.text
        val start = text.indexOf("missing")
        DorisPipes.setExecMark(project, file.virtualFile.url, DorisPipes.ExecMark(start, start + 7, "server rejected column", text.hashCode()))
        val errors = DorisErrorAnnotator().run {
            doAnnotate(collectInformation(file)!!).errors
        }
        assertTrue(errors.any { it.message == "Doris (server): server rejected column" })
    }

    private fun dorisFile(name: String, sql: String): com.intellij.psi.PsiFile {
        val file = myFixture.configureByText(name, sql)
        SqlDialectMappings.getInstance(project).setMapping(file.virtualFile, DorisSqlDialect.INSTANCE)
        return myFixture.file
    }

    private fun infoAt(text: String, token: String, description: String): HighlightInfo {
        val start = text.indexOf(token).also { check(it >= 0) }
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
            .range(start, start + token.length)
            .descriptionAndTooltip(description)
            .create()!!
    }
}
