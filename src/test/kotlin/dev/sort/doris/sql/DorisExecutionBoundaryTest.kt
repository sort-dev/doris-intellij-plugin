package dev.sort.doris.sql

import com.intellij.lang.Language
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.database.script.ScriptModel
import com.intellij.sql.script.SqlScriptModel
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DorisExecutionBoundaryTest : BasePlatformTestCase() {
    fun testTemporaryPartitionInsertIsOneExecutableStatement() {
        val sql = """
            SELECT 0;

            INSERT INTO pm_auto_swap_hourly TEMPORARY PARTITION(p_20240701_day)
            SELECT event_at, id, amount, note
            FROM pm_auto_swap_hourly
            WHERE event_at >= '2024-07-01 00:00:00'
              AND event_at <  '2024-07-02 00:00:00';

            SELECT 1;
        """.trimIndent()
        val file = PsiFileFactory.getInstance(project)
            .createFileFromText("execution.sql", Language.findLanguageByID("DorisSQL")!!, sql, false, true)!!
        assertInsertExecutionRange(sql, SqlScriptModel(file))
    }

    fun testTemporaryPartitionInsertSurvivesIncrementalConsoleEdit() {
        val expected = """
            INSERT INTO pm_auto_swap_hourly TEMPORARY PARTITION(p_20240701_day)
            SELECT event_at, id, amount, note
            FROM pm_auto_swap_hourly
            WHERE event_at >= '2024-07-01 00:00:00'
              AND event_at <  '2024-07-02 00:00:00';
        """.trimIndent()
        val initial = expected.replace("TEMPORARY ", "")
        val file = myFixture.configureByText("incremental.sql", initial)
        val model = SqlScriptModel<PsiElement>(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        try {
            model.statements().toList() // initialize the live console model before the edit
            val partition = myFixture.editor.document.text.indexOf("PARTITION")
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(partition, "TEMPORARY ")
            }
            PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
            assertInsertExecutionRange(expected, model, expectedStatements = 1, dispose = false)
        } finally {
            model.dispose()
        }
    }

    private fun assertInsertExecutionRange(
        sql: String,
        model: ScriptModel<PsiElement>,
        expectedStatements: Int = 3,
        dispose: Boolean = true,
    ) {
        try {
            val statements = model.statements().map {
                it.range().shiftRight(it.rangeOffset().toInt()) to it.query()
            }.toList()
            assertEquals("DataGrip execution statement count", expectedStatements, statements.size)
            val insertOffset = sql.indexOf("INSERT")
            val matching = statements.filter { it.first.containsOffset(insertOffset) }
            val debug = statements.joinToString("\n") {
                "range=${it.first} query=${it.second.replace('\n', ' ')}"
            }
            assertEquals("INSERT execution range\n$debug", 1, matching.size)
            val expected = sql.substring(insertOffset, sql.indexOf(';', insertOffset))
            assertEquals(expected, matching.single().second)
            assertEquals(TextRange(insertOffset, insertOffset + expected.length), matching.single().first)
        } finally {
            if (dispose) model.dispose()
        }
    }
}
