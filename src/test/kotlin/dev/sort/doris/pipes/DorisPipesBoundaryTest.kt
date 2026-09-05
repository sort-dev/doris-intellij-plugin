package dev.sort.doris.pipes

import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.psi.SqlStatement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.sql.DorisSqlDialect

class DorisPipesBoundaryTest : BasePlatformTestCase() {
    private var previousPipes: String? = null
    private var previousReplay: String? = null

    override fun setUp() {
        super.setUp()
        previousPipes = System.getProperty("doris.pipes")
        previousReplay = System.getProperty("doris.replay.poc")
        System.setProperty("doris.pipes", "true")
        assertTrue("fixture must have the pipe engine available", DorisPipes.enabled)
    }

    override fun tearDown() {
        try {
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
        SqlDialectMappings.getInstance(project).setMapping(file.virtualFile, DorisSqlDialect.INSTANCE)
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
