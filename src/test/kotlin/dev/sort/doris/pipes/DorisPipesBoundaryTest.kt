package dev.sort.doris.pipes

import com.intellij.psi.PsiFileFactory
import com.intellij.openapi.components.service
import com.intellij.psi.util.PsiTreeUtil
import dev.sort.doris.setSqlDialectMapping
import com.intellij.sql.psi.SqlStatement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.sql.DorisSqlDialect
import com.intellij.testFramework.PlatformTestUtil

class DorisPipesBoundaryTest : BasePlatformTestCase() {
    private var previousPipes: String? = null
    private var previousReplay: String? = null
    private var previousEnabled = false

    override fun setUp() {
        super.setUp()
        previousPipes = System.getProperty("doris.pipes")
        previousReplay = System.getProperty("doris.replay.poc")
        previousEnabled = project.service<DorisPipesSettings>().enabled
        System.setProperty("doris.pipes", "true")
        project.service<DorisPipesSettings>().enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(DorisPipes.isEnabled(project))
    }

    override fun tearDown() {
        try {
            project.service<DorisPipesSettings>().enabled = previousEnabled
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (previousPipes == null) System.clearProperty("doris.pipes")
            else System.setProperty("doris.pipes", previousPipes!!)
            if (previousReplay == null) System.clearProperty("doris.replay.poc")
            else System.setProperty("doris.replay.poc", previousReplay!!)
        } finally {
            super.tearDown()
        }
    }

    fun testRangesAgreeWithPipePsiForQuotedAndCommentedSemicolons() {
        val statements = listOf(
            "FROM t |> SELECT * -- ;\n|> WHERE tenant_id = 7;",
            "FROM t |> WHERE s = 'a;b' |> SELECT s;",
            "FROM t |> WHERE s = 'a\\';b' |> SELECT s;",
            "FROM t |> WHERE s = \"a;\"\"b\" |> SELECT s;",
            "FROM `ta;ble` |> SELECT `co``;lumn`;",
            "FROM t |> SELECT * /* ; */ |> LIMIT 5;",
            "FROM t |> WHERE s = '\uD83D\uDE00;\nvalue' |> LIMIT 5;",
        )
        for (replay in listOf("false", "true")) {
            System.setProperty("doris.replay.poc", replay)
            for (statement in statements) {
                val text = "SELECT 0;\n$statement\nSELECT 2;"
                val file = PsiFileFactory.getInstance(project)
                    .createFileFromText("pipes.sql", DorisSqlDialect.INSTANCE, text)
                val psiStatements = PsiTreeUtil.findChildrenOfType(file, SqlStatement::class.java)
                    .filter { PsiTreeUtil.getParentOfType(it, SqlStatement::class.java) == null }
                assertEquals("replay=$replay: $statement", 3, psiStatements.size)
                for ((chunk, psi) in DorisPipes.chunks(text).zip(psiStatements)) {
                    assertEquals(chunk.text.trim().removeSuffix(";"), psi.text.trim().removeSuffix(";"))
                    assertEquals(
                        chunk.startOffset + chunk.text.length - chunk.text.trimStart().length,
                        psi.textRange.startOffset,
                    )
                }
            }
        }
    }

    fun testPreviewUsesWholeChunkRegardlessOfEditorSelection() {
        val statement = "FROM t |> SELECT * -- ;\n|> WHERE tenant_id = 7;"
        val text = "SELECT 0;\n$statement\nSELECT 2;"
        val file = myFixture.configureByText("pipes.sql", text)
        setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(text.indexOf("WHERE"))
        editor.selectionModel.setSelection(text.indexOf("FROM"), text.indexOf(" --"))
        val selection = editor.selectionModel.selectedText
        val chunk = DorisPipesUi.pipeChunkAtCaret(myFixture.file, editor)!!
        assertEquals("\n$statement", chunk.text)
        assertEquals(selection, editor.selectionModel.selectedText)
        assertEquals(text, editor.document.text)
        assertEquals(DorisPipes.chunkAt(text, editor.caretModel.offset), chunk)
    }
}
