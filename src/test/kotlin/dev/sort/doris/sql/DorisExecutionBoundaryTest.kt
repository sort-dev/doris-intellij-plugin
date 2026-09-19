package dev.sort.doris.sql

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.script.ScriptModel
import com.intellij.database.settings.DatabaseSettings
import com.intellij.lang.Language
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.sql.script.SqlScriptModel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.setSqlDialectMapping

class DorisExecutionBoundaryTest : BasePlatformTestCase() {
    fun testShownStatementCaretHighlightWithProductionDefaults() {
        val sql = """
            select 1;

            INSERT INTO pm_swap_hourly TEMPORARY PARTITION(p_20240502_day)
            SELECT event_at, id, amount, note
            FROM pm_swap_hourly
            WHERE event_at >= '2024-05-02 00:00:00'
              AND event_at <  '2024-05-03 00:00:00';

            SELECT 'formal' AS partition_kind, COUNT(*) AS row_count FROM pm_swap_hourly;
        """.trimIndent()
        val oldReplay = System.getProperty(DorisReplay.PROPERTY)
        try {
            System.clearProperty(DorisReplay.PROPERTY)
            configureDorisFile(sql)
            assertCaretAndSelectionRanges(sql)
        } finally {
            if (oldReplay == null) System.clearProperty(DorisReplay.PROPERTY)
            else System.setProperty(DorisReplay.PROPERTY, oldReplay)
        }
    }

    fun testTemporaryPartitionInsertAfterRangePartitionDdl() {
        val sql = """
            ALTER TABLE pm_swap_hourly ADD TEMPORARY PARTITION p_20240502_day
            VALUES [('2024-05-02 00:00:00'), ('2024-05-03 00:00:00'));

            select 1;

            INSERT INTO pm_swap_hourly TEMPORARY PARTITION(p_20240502_day)
            SELECT event_at, id, amount, note
            FROM pm_swap_hourly
            WHERE event_at >= '2024-05-02 00:00:00'
              AND event_at <  '2024-05-03 00:00:00';

            SELECT 1;
        """.trimIndent()
        val oldReplay = System.getProperty(DorisReplay.PROPERTY)
        try {
            for (replay in listOf("true", "false")) {
                System.setProperty(DorisReplay.PROPERTY, replay)
                val file = configureDorisFile(sql)
                assertInsertExecutionRange(sql, SqlScriptModel(file), expectedStatements = 4)
                assertCaretAndSelectionRanges(sql)
            }
        } finally {
            if (oldReplay == null) System.clearProperty(DorisReplay.PROPERTY)
            else System.setProperty(DorisReplay.PROPERTY, oldReplay)
        }
    }

    fun testPastingAndEditingInsertAfterRangePartitionDdl() {
        val prefix = """
            ALTER TABLE pm_swap_hourly ADD TEMPORARY PARTITION p_20240502_day
            VALUES [('2024-05-02 00:00:00'), ('2024-05-03 00:00:00'));
            select 1;

        """.trimIndent()
        val insert = """
            INSERT INTO pm_swap_hourly TEMPORARY PARTITION(p_20240502_day)
            SELECT event_at, id, amount, note
            FROM pm_swap_hourly
            WHERE event_at >= '2024-05-02 00:00:00'
              AND event_at <  '2024-05-03 00:00:00';
        """.trimIndent()
        val oldReplay = System.getProperty(DorisReplay.PROPERTY)
        try {
            System.clearProperty(DorisReplay.PROPERTY)
            val file = configureDorisFile(prefix)
            val model = SqlScriptModel<PsiElement>(project, file.virtualFile, DorisSqlDialect.INSTANCE)
            try {
                model.statements().toList()
                WriteCommandAction.runWriteCommandAction(project) {
                    myFixture.editor.document.insertString(prefix.length, insert)
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                assertInsertExecutionRange(prefix + insert, model, dispose = false)
                assertCaretAndSelectionRanges(prefix + insert)
                val note = (prefix + insert).indexOf("note", prefix.length)
                WriteCommandAction.runWriteCommandAction(project) {
                    myFixture.editor.document.replaceString(note, note + 4, "note AS saved_note")
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                val edited = myFixture.editor.document.text
                assertInsertExecutionRange(edited, model, dispose = false)
                assertCaretAndSelectionRanges(edited)
            } finally {
                model.dispose()
            }
        } finally {
            if (oldReplay == null) System.clearProperty(DorisReplay.PROPERTY)
            else System.setProperty(DorisReplay.PROPERTY, oldReplay)
        }
    }

    private fun configureDorisFile(sql: String): PsiFile {
        val original = myFixture.configureByText("partition-swap.sql", sql)
        setSqlDialectMapping(project, original.virtualFile, DorisSqlDialect.INSTANCE)
        return PsiManager.getInstance(project).findFile(original.virtualFile)!!.also {
            assertSame(DorisSqlDialect.INSTANCE, it.language)
        }
    }

    private fun assertCaretAndSelectionRanges(sql: String) {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(myFixture.editor.document)!!
        val start = sql.indexOf("INSERT")
        val end = sql.indexOf(';', start)
        val expected = listOf(TextRange(start, end) to sql.substring(start, end))

        // These are the same entry points StatementHighlightingPassFactory uses for the green box.
        fun chosen(option: DatabaseSettings.ExecOption): List<Pair<TextRange, String>>? {
            val element = JdbcConsoleProvider.elementAt(file, null, myFixture.editor)!!
            val info = JdbcConsoleProvider.findScriptModel(file, element, myFixture.editor, option)!!
            var actual: List<Pair<TextRange, String>>? = null
            JdbcConsoleProvider.chooseStatements(info, null, false) { selected ->
                // StatementIt is a mutable iterator view: copy values while iterating.
                actual = selected.statements().map {
                    it.range().shiftRight(it.rangeOffset().toInt()) to it.query()
                }.toList()
            }
            return actual
        }

        for (word in listOf("INSERT", "TEMPORARY", "SELECT event_at", "FROM pm_swap_hourly", "WHERE")) {
            myFixture.editor.selectionModel.removeSelection()
            myFixture.editor.caretModel.moveToOffset(sql.indexOf(word, start) + 1)
            for (scope in listOf(
                DatabaseSettings.EXECUTE_INSIDE_SHOW_CHOOSER,
                DatabaseSettings.EXECUTE_INSIDE_SMALLEST,
                DatabaseSettings.EXECUTE_INSIDE_LARGEST,
            )) {
                assertEquals("caret=$word scope=$scope", expected,
                    chosen(DatabaseSettings.ExecOption().apply { execInside = scope }))
            }
        }
        myFixture.editor.selectionModel.setSelection(start, end)
        for (scope in listOf(
            DatabaseSettings.EXECUTE_SELECTION_EXACTLY_ONE,
            DatabaseSettings.EXECUTE_SELECTION_EXACTLY_SCRIPT,
            DatabaseSettings.EXECUTE_SELECTION_SMART_EXPAND,
        )) {
            assertEquals("selection scope=$scope", expected,
                chosen(DatabaseSettings.ExecOption().apply { execSelection = scope }))
        }
        myFixture.editor.selectionModel.removeSelection()
    }

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
            val debug = statements.joinToString("\n") {
                "range=${it.first} query=${it.second.replace('\n', ' ')}"
            }
            assertEquals("DataGrip execution statement count\n$debug", expectedStatements, statements.size)
            val insertOffset = sql.indexOf("INSERT")
            val matching = statements.filter { it.first.containsOffset(insertOffset) }
            assertEquals("INSERT execution range\n$debug", 1, matching.size)
            val expected = sql.substring(insertOffset, sql.indexOf(';', insertOffset))
            assertEquals(expected, matching.single().second)
            assertEquals(TextRange(insertOffset, insertOffset + expected.length), matching.single().first)
        } finally {
            if (dispose) model.dispose()
        }
    }
}
