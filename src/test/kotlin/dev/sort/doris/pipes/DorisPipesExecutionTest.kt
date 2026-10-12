package dev.sort.doris.pipes

import dev.brikk.house.sql.intellij.pipe.PipeTranslation

import com.intellij.database.Dbms
import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.datagrid.GridDataRequest
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.script.ScriptModel
import com.intellij.database.settings.DatabaseSettings
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopup
import dev.brikk.house.sql.intellij.pipe.PipePreviewIntention
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.brikk.house.sql.intellij.pipe.lower
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.LanguageTextField
import com.intellij.ui.UiInterceptors
import dev.brikk.house.sql.intellij.SqlExecutionRefusal
import dev.brikk.house.sql.intellij.TranslatingScriptModel
import dev.brikk.house.sql.intellij.ExecutionClaim
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor
import dev.brikk.house.sql.intellij.pipe.PipeRun
import dev.brikk.house.sql.intellij.pipe.PipeSubmitListener
import dev.brikk.house.sql.intellij.notifyEscaped
import com.intellij.database.console.JdbcConsoleProvider
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.sort.doris.DorisDbms
import dev.sort.doris.setSqlDialectMapping
import dev.sort.doris.sql.DorisSqlDialect
import org.antlr.v4.runtime.Token
import org.apache.doris.nereids.DorisLexer
import org.apache.doris.sqlparser.DorisSqlParser
import java.util.UUID
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Real Execute interception, console construction, platform Info preparation, and request creation.
 * Only the session/bus are fake. Requests stop at processRequest, with no engine or JDBC connection.
 * The previous action is a sentinel, never stock Execute, even if interception unexpectedly delegates.
 */
class DorisPipesExecutionTest : BasePlatformTestCase() {
    private val settings get() = project.service<DorisPipesSettings>()
    private var previousFlag: String? = null
    private var previousEnabled = false

    override fun setUp() {
        super.setUp()
        previousFlag = System.getProperty(DorisPipes.PROPERTY)
        previousEnabled = settings.enabled
        System.clearProperty(DorisPipes.PROPERTY)
        settings.enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            settings.enabled = previousEnabled
            settings.execMarks.clear()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (previousFlag == null) System.clearProperty(DorisPipes.PROPERTY)
            else System.setProperty(DorisPipes.PROPERTY, previousFlag!!)
        } finally {
            super.tearDown()
        }
    }

    fun testWarningFreePipeSubmitsThroughRealDefaultInterceptor() {
        val sql = "FROM offline_rows |> SELECT id |> WHERE id > 0"
        val translated = DorisPipesEngine.transpile(sql) as PipeTranslation.Lowered
        assertEmpty(translated.result!!.unsupportedMessages)
        ExecutionFixture(sql).use { fixture ->
            for (variant in 1..4) {
                fixture.editor.selectionModel.setSelection(0, sql.length)
                fixture.execute(variant)
                assertEquals(variant, fixture.requests.size)
                val request = fixture.requests.last() as DataRequest.QueryRequest
                assertEquals(translated.dorisSql, request.query)
                assertFalse(request.query.contains("|>"))
                assertSame(fixture.console, request.owner)
                val coupled = request as DataRequest.CoupledWithEditor
                assertSame(fixture.editor, coupled.editor)
                assertEquals(TextRange(0, sql.length), coupled.range)
                assertSame(request, coupled.request)
                assertEmpty(fixture.previousEvents)
                assertEmpty(fixture.notifications)
                assertEquals(sql, fixture.editor.document.text)
            }
        }
    }

    fun testUnselectedPipeUsesTheRealPlatformPreparation() {
        val sql = "FROM offline_rows |> SELECT id"
        ExecutionFixture(sql).use { fixture ->
            assertFalse(fixture.editor.selectionModel.hasSelection())
            fixture.execute()
            assertEquals(1, fixture.requests.size)
            val request = fixture.requests.single() as DataRequest.QueryRequest
            assertEquals((DorisPipesEngine.transpile(sql) as PipeTranslation.Lowered).dorisSql, request.query)
            assertEquals(TextRange(0, sql.length), (request as DataRequest.CoupledWithEditor).range)
            assertEmpty(fixture.previousEvents)
            assertEquals(sql, fixture.editor.document.text)
        }
    }

    fun testEditedStatementDoesNotReuseItsPreviousTranslationAfterRunningAnotherStatement() {
        val original = "FROM offline_rows |> WHERE id = 1 |> SELECT id"
        val updated = "FROM offline_rows |> WHERE id = 9 |> SELECT id"
        val other = "FROM offline_rows |> WHERE id = 2 |> SELECT id"
        val sql = "$original;\n$other;"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.selectionModel.setSelection(0, original.length)
            fixture.editor.caretModel.moveToOffset(sql.indexOf("id = 1"))
            fixture.execute()
            assertEquals(
                (DorisPipesEngine.transpile(original) as PipeTranslation.Lowered).dorisSql,
                (fixture.requests.last() as DataRequest.QueryRequest).query,
            )

            WriteCommandAction.runWriteCommandAction(project) {
                val start = fixture.editor.document.text.indexOf("id = 1")
                fixture.editor.document.replaceString(start, start + "id = 1".length, "id = 9")
            }
            val otherStart = fixture.editor.document.text.indexOf(other)
            fixture.editor.selectionModel.setSelection(otherStart, otherStart + other.length)
            fixture.editor.caretModel.moveToOffset(otherStart + other.indexOf("id = 2"))
            fixture.execute()
            assertEquals(
                (DorisPipesEngine.transpile(other) as PipeTranslation.Lowered).dorisSql,
                (fixture.requests.last() as DataRequest.QueryRequest).query,
            )

            fixture.editor.selectionModel.setSelection(0, updated.length)
            fixture.editor.caretModel.moveToOffset(updated.indexOf("id = 9"))
            fixture.execute()
            assertEquals(
                (DorisPipesEngine.transpile(updated) as PipeTranslation.Lowered).dorisSql,
                (fixture.requests.last() as DataRequest.QueryRequest).query,
            )
            assertFalse((fixture.requests.last() as DataRequest.QueryRequest).query.contains("id = 1"))
        }
    }

    fun testCachedStatementTextNeverOverridesTheCurrentDocumentSlice() {
        val old = "FROM offline_rows |> WHERE id = 1"
        val current = "FROM offline_rows |> WHERE id = 9"
        ExecutionFixture(current).use { fixture ->
            for (cached in listOf(old, "SELECT 1")) {
                val model = TranslatingScriptModel(cachedQueryModel(fixture.console.scriptModel, cached), fixture.editor, DorisTestExecutionAdapter)
                model.finalizeTranslations(fixture.console.pStorage)
                val statement = model.statements().map { it.text() to it.query() }.toList().single()
                assertEquals(current, statement.first)
                assertEquals((DorisPipesEngine.transpile(current) as PipeTranslation.Lowered).sql, statement.second)
            }
            fixture.replaceSql("SELECT 1")
            val failure = runCatching {
                TranslatingScriptModel(cachedQueryModel(fixture.console.scriptModel, old), fixture.editor, DorisTestExecutionAdapter)
            }.exceptionOrNull()
            assertTrue(failure is SqlExecutionRefusal)
            assertTrue((failure as SqlExecutionRefusal).message!!.contains("prepared statement changed"))
        }
    }

    fun testPreparedModelRejectsEditAndRevertBeforeSubmission() {
        ExecutionFixture(safeProgram).use { fixture ->
            val model = TranslatingScriptModel(fixture.console.scriptModel, fixture.editor, DorisTestExecutionAdapter)
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.editor.document.insertString(0, " ")
                fixture.editor.document.deleteString(0, 1)
            }
            assertFalse(model.isActual)
            val refusal = runCatching { model.finalizeTranslations(fixture.console.pStorage) }.exceptionOrNull()
            assertNotNull(refusal)
            assertTrue(refusal is SqlExecutionRefusal)
            assertTrue(refusal!!.message!!, refusal.message!!.contains("SQL changed during preparation"))
            assertEmpty(fixture.requests)
        }
    }

    fun testEditDuringTrackingStopsBeforeAnyMixedScriptRequest() {
        val sql = "SELECT 1; $safeProgram;"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("FROM"))
            var editedAtTracking = false
            fixture.recording.onAuditorAdded = { auditor ->
                if (auditor.javaClass.name.startsWith(DorisPipesExecution::class.java.name + "$")) {
                    editedAtTracking = true
                    WriteCommandAction.runWriteCommandAction(project) { fixture.editor.document.insertString(0, " ") }
                }
            }
            val settings = DatabaseSettings.getSettings()
            val old = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT
            }
            try {
                val failure = runCatching { fixture.execute() }.exceptionOrNull()
                assertTrue("Inject the edit into Doris tracking, not platform UI construction", editedAtTracking)
                assertNull(failure)
                val notification = fixture.notifications.single()
                assertEquals(NotificationType.ERROR, notification.type)
                assertTrue(notification.content, notificationText(notification).contains("SQL changed during preparation"))
                assertEmpty(fixture.requests)
                assertEmpty(fixture.previousEvents)
            } finally { settings.execOptions[0] = old }
        }
    }

    fun testCancellationDuringTrackingNeverSubmitsOrDelegates() {
        ExecutionFixture(safeProgram).use { fixture ->
            val canceled = com.intellij.openapi.progress.ProcessCanceledException()
            var canceledAtTracking = false
            fixture.recording.onAuditorAdded = { auditor ->
                if (auditor.javaClass.name.startsWith(DorisPipesExecution::class.java.name + "$")) {
                    canceledAtTracking = true
                    throw canceled
                }
            }
            val failure = runCatching { fixture.execute() }.exceptionOrNull()
            assertTrue("Cancel Doris tracking, not a platform console-view constructor", canceledAtTracking)
            assertSame(canceled, failure)
            assertEmpty(fixture.requests)
            assertEmpty(fixture.previousEvents)
            assertEmpty(fixture.notifications)
        }
    }

    fun testEditAndRevertDuringStockSaveIsStillRejected() {
        ExecutionFixture(safeProgram).use { fixture ->
            var edited = false
            val connection = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect()
            connection.subscribe(com.intellij.AppTopics.FILE_DOCUMENT_SYNC,
                object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
                    override fun beforeAllDocumentsSaving() {
                        if (edited) return
                        edited = true
                        WriteCommandAction.runWriteCommandAction(project) {
                            fixture.editor.document.insertString(0, " ")
                            fixture.editor.document.deleteString(0, 1)
                        }
                    }
                })
            try {
                val failure = runCatching { fixture.execute() }.exceptionOrNull()
                assertTrue("The stock save hook must run", edited)
                assertNull(failure)
                val notification = fixture.notifications.single()
                assertEquals(NotificationType.ERROR, notification.type)
                assertTrue(notificationText(notification).contains("SQL changed during preparation"))
                assertEmpty(fixture.requests)
                assertEmpty(fixture.previousEvents)
                assertEquals(safeProgram, fixture.editor.document.text)
            } finally { connection.disconnect() }
        }
    }

    fun testAuthoredMapPreservesUnicodeAndMixedScriptSlots() {
        val pipe = "FROM offline_rows |> SELECT '😀' AS marker, id |> WHERE id > 0"
        ExecutionFixture("SELECT 1; $pipe; SELECT 2;").use { fixture ->
            fixture.executeScript()
            val statements = fixture.runs.single().statements
            assertEquals(3, statements.size)
            assertNull(statements.first())
            assertNull(statements.last())
            val plan = statements[1]!!
            assertNotNull(plan.sourceMap)
            val outputOffset = plan.sql.indexOf("id")
            val prefix = plan.sql.substring(0, outputOffset)
            val line = 1 + prefix.count { it == '\n' }
            val linePrefix = prefix.substringAfterLast('\n')
            val pos = linePrefix.codePointCount(0, linePrefix.length)
            val mapped = mappedPipeError("(line $line, pos $pos)", plan.source, plan.sourceMap)!!
            assertEquals("id", plan.source.substring(mapped.startOffset!!, mapped.endOffset!! + 1))
            val documentStart = plan.range.startOffset + mapped.startOffset
            assertEquals("id", fixture.editor.document.text.substring(documentStart, documentStart + 2))
        }
    }

    fun testParameterExpansionWithholdsAuthoredMapInsteadOfGuessing() {
        val sql = "FROM offline_rows |> WHERE id = :id |> SELECT id"
        ExecutionFixture(sql).use { fixture ->
            ShowSqlParametersPanelAction.getStorage(fixture.console).putValue("id", "17000")
            val model = TranslatingScriptModel(fixture.console.scriptModel, fixture.editor, DorisTestExecutionAdapter)
            assertContainsElements(model.parameters().map { it.name() }.toList(), "id")
            fixture.execute()
            val plan = fixture.runs.single().statements.single()!!
            assertTrue(plan.sql, "17000" in plan.sql)
            assertNull(plan.sourceMap)
            assertNull(mappedPipeError("(line 1, pos 7)", plan.source, plan.sourceMap))
            assertEquals(sql, fixture.editor.document.text)
        }
    }

    fun testAuditorMapsThePipeQueryIndexAfterPlainScriptSlots() {
        val pipe = "FROM offline_rows |> SELECT '😀' AS marker, id |> WHERE id > 0"
        ExecutionFixture("SELECT 1; $pipe; SELECT 2;").use { fixture ->
            var auditor: com.intellij.database.datagrid.DataAuditor? = null
            fixture.recording.onAuditorAdded = {
                if (it.javaClass.name.startsWith(DorisPipesExecution::class.java.name + "$")) auditor = it
            }
            val text = fixture.editor.document.text
            val oldMark = DorisPipes.ExecMark(0, 1, "previous run", text.hashCode())
            DorisPipes.setExecMark(project, fixture.file.virtualFile.url, oldMark)
            fixture.executeScript()
            val plain = fixture.requests.single() as ConsoleDataRequest
            assertEquals(0, plain.queryIndex)
            val observer = checkNotNull(auditor)
            val info = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(com.intellij.database.connection.throwable.info.ErrorInfo::class.java)) { _, method, _ ->
                when (method.name) {
                    "getMessage" -> "Unknown column (line 1, pos 7)"
                    else -> error("Unexpected error-info method ${method.name}")
                }
            } as com.intellij.database.connection.throwable.info.ErrorInfo
            observer.error(DataRequest.Context(fixture.session.messageBus.dataProducer,
                DataRequest.newOutputHandler(), plain, DataRequest.AUTO_COMMIT), info)
            assertEquals(oldMark, DorisPipes.execMarkFor(project, fixture.file.virtualFile.url, text))
            assertEmpty(fixture.notifications)
            plain.onFinished()
            val request = fixture.requests.last() as ConsoleDataRequest
            assertEquals(1, request.queryIndex)
            assertNull(DorisPipes.execMarkFor(project, fixture.file.virtualFile.url, text))
            val translated = fixture.runs.single().statements[1]!!
            val outputPrefix = translated.sql.take(translated.sql.indexOf("id"))
            val line = 1 + outputPrefix.count { it == '\n' }
            val linePrefix = outputPrefix.substringAfterLast('\n')
            val position = linePrefix.codePointCount(0, linePrefix.length)
            val message = "Unknown column (line $line, pos $position)"
            val error = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(com.intellij.database.connection.throwable.info.ErrorInfo::class.java)) { _, method, _ ->
                when (method.name) {
                    "getMessage" -> message
                    else -> error("Unexpected error-info method ${method.name}")
                }
            } as com.intellij.database.connection.throwable.info.ErrorInfo
            observer.error(DataRequest.Context(fixture.session.messageBus.dataProducer,
                DataRequest.newOutputHandler(), request, DataRequest.AUTO_COMMIT), error)
            val mark = DorisPipes.execMarkFor(project, fixture.file.virtualFile.url, text)!!
            assertEquals("id", text.substring(mark.start, mark.end))
            assertEquals(message, mark.message)
            assertEquals(text.hashCode(), mark.docHash)
            assertEquals(NotificationType.WARNING, fixture.notifications.single().type)
        }
    }

    fun testOutsideScriptTailKeepsTheLeadingCommentIntact() {
        val sql = "SELECT 0; /* ) caret here 😀 */ $safeProgram;"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("caret"))
            val settings = DatabaseSettings.getSettings()
            val old = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_SMALLEST
                execOutside = DatabaseSettings.EXECUTE_OUTSIDE_SCRIPT_TAIL
            }
            try { fixture.execute() } finally { settings.execOptions[0] = old }
            assertEquals(1, fixture.requests.size)
            assertFalse((fixture.requests.single() as DataRequest.QueryRequest).query.contains("|>"))
            assertEmpty(fixture.previousEvents)
        }
    }

    fun testOrdinarySqlAndQuotedOrCommentMarkersDelegateWithTheSameEvent() {
        for (sql in listOf(
            "SELECT 1", "SELECT '|>' AS marker", "SELECT \"|>\" AS marker",
            "SELECT `|>` FROM offline_rows", "SELECT \$\$|>\$\$ AS marker",
            "SELECT 1 -- |>\n", "SELECT /* |> */ 1", "-- |> only a comment", "/* |> */",
            "SELECT '|>' +", "SELECT `|>` FROM", "SELECT 1 /* |>",
            "SELECT 'unterminated |> ; SELECT 2", "SELECT \"unterminated |>", "SELECT `unterminated |>",
            "SELECT \$\$unfinished; FROM offline_rows |> SELECT id", "SELECT /*+ unfinished |> ; SELECT 2",
            "SELECT /* outer /* inner */ unfinished; FROM offline_rows |> SELECT id",
        )) {
            ExecutionFixture(sql).use { fixture ->
                var delegated = 0
                for (variant in 1..4) {
                    for (selected in listOf(false, true)) {
                        if (selected) fixture.editor.selectionModel.setSelection(0, sql.length)
                        else fixture.editor.selectionModel.removeSelection()
                        val event = fixture.execute(variant)
                        assertEquals("variant=$variant selected=$selected sql=$sql", ++delegated, fixture.previousEvents.size)
                        assertSame(event, fixture.previousEvents.last())
                        assertEmpty(fixture.requests)
                        assertEmpty(fixture.notifications)
                        assertEquals(sql, fixture.editor.document.text)
                    }
                }
            }
        }
    }

    fun testLossyWarningsBlockEveryVariantWithoutLeakingToTheNextQuery() {
        ExecutionFixture(warningPrograms.first()).use { fixture ->
            for (sql in warningPrograms) {
                val translation = DorisPipesEngine.transpile(sql) as PipeTranslation.Lowered
                assertNotNull(translation.executionError)
                for (variant in 1..4) {
                    val submittedBefore = fixture.requests.size
                    fixture.replaceSql(sql)
                    fixture.execute(variant)
                    assertEquals("warning query must not submit: variant=$variant sql=$sql", submittedBefore, fixture.requests.size)
                    assertEmpty(fixture.previousEvents)
                    assertEquals(1, fixture.notifications.size)
                    val notification = fixture.notifications.last()
                    assertEquals(NotificationType.ERROR, notification.type)
                    val text = notificationText(notification)
                    assertEquals(translation.executionError!!.message, text)
                    for (warning in translation.unsupportedMessages) assertTrue(text, warning in text)
                    assertEquals(sql, fixture.editor.document.text)

                    fixture.replaceSql(safeProgram)
                    fixture.execute(variant)
                    assertEquals(submittedBefore + 1, fixture.requests.size)
                    assertEquals((DorisPipesEngine.transpile(safeProgram) as PipeTranslation.Lowered).dorisSql,
                        (fixture.requests.last() as DataRequest.QueryRequest).query)
                    assertEquals("the next query must not inherit warnings", 1, fixture.notifications.size)
                    assertEmpty(fixture.previousEvents)
                    assertEquals(safeProgram, fixture.editor.document.text)
                    fixture.notifications.forEach { it.expire() }
                    fixture.notifications.clear()
                }
            }
        }
    }

    fun testNativeInvalidGeneratedSqlBlocksEveryVariantWithoutRawFallback() {
        val sql = "SELECT id FROM offline_rows LIMIT -1 |> ORDER BY 1"
        val translation = DorisPipesEngine.transpile(sql) as PipeTranslation.Lowered
        assertEmpty(translation.unsupportedMessages)
        assertNotNull(translation.validationError)
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.selectionModel.setSelection(0, sql.length)
            for (variant in 1..4) {
                fixture.execute(variant)
                assertEmpty(fixture.requests)
                assertEmpty(fixture.previousEvents)
                assertEquals(variant, fixture.notifications.size)
                assertEquals(NotificationType.ERROR, fixture.notifications.last().type)
                assertTrue(notificationText(fixture.notifications.last()).contains("Generated SQL"))
                assertEquals(sql, fixture.editor.document.text)
            }
        }
    }

    fun testDisabledProjectAndNonDorisConsoleDelegateEveryVariant() {
        for (disabled in listOf(true, false)) {
            ExecutionFixture(safeProgram, if (disabled) "doris" else "mysql").use { fixture ->
                if (disabled) settings.enabled = false
                else assertSame(Dbms.MYSQL, fixture.point.dbms)
                try {
                    fixture.editor.selectionModel.setSelection(0, safeProgram.length)
                    for (variant in 1..4) {
                        val event = fixture.execute(variant)
                        assertEquals(variant, fixture.previousEvents.size)
                        assertSame(event, fixture.previousEvents.last())
                        assertEmpty(fixture.requests)
                        assertEmpty(fixture.notifications)
                    }
                    assertEquals(safeProgram, fixture.editor.document.text)
                } finally {
                    settings.enabled = true
                }
            }
        }
    }

    fun testPlatformScopeRunsWholeMixedScriptWithParametersAndNewTab() {
        val pipe = "FROM offline_rows |> WHERE id = :id |> SELECT id"
        val sql = "SELECT 1; $pipe; SELECT 2;"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("WHERE"))
            ShowSqlParametersPanelAction.getStorage(fixture.console).putValue("id", "17")
            val settings = DatabaseSettings.getSettings()
            val old = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT
                newTab = true
            }
            try {
                fixture.execute()
                while (fixture.requests.size < 3) (fixture.requests.last() as ConsoleDataRequest).onFinished()
            } finally {
                settings.execOptions[0] = old
            }
            val requests = fixture.requests.map { it as ConsoleDataRequest }
            assertEquals(listOf("SELECT 1", "SELECT 2"), listOf(requests.first().query.trim(), requests.last().query.trim()))
            assertTrue(requests[1].query, "17" in requests[1].query)
            assertFalse(requests[1].query, ":id" in requests[1].query || "|>" in requests[1].query)
            assertTrue(requests.all { it.owner === fixture.console })
            assertTrue(requests.all { it.newTab })
            assertEmpty(fixture.previousEvents)
            assertEmpty(fixture.notifications)
        }
    }

    fun testScriptTailScopeStartsAtThePipeAndKeepsFollowingSql() {
        val sql = "SELECT 1; $safeProgram; SELECT 2;"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("FROM"))
            val settings = DatabaseSettings.getSettings()
            val old = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_SCRIPT_TAIL
            }
            try {
                fixture.execute()
                while (fixture.requests.size < 2) (fixture.requests.last() as ConsoleDataRequest).onFinished()
            } finally {
                settings.execOptions[0] = old
            }
            assertFalse((fixture.requests[0] as DataRequest.QueryRequest).query, "|>" in (fixture.requests[0] as DataRequest.QueryRequest).query)
            assertEquals("SELECT 2", (fixture.requests[1] as DataRequest.QueryRequest).query.trim())
        }
    }

    fun testInvalidParameterizedPipeFinalizesBeforePlainPrefixExecution() {
        val sql = "UPDATE offline_rows SET id = id; SELECT id FROM offline_rows LIMIT :limit |> ORDER BY 1;"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("SELECT"))
            ShowSqlParametersPanelAction.getStorage(fixture.console).putValue("limit", "-1")
            val settings = DatabaseSettings.getSettings()
            val old = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT
            }
            try {
                fixture.execute()
            } finally {
                settings.execOptions[0] = old
            }
            assertEmpty(fixture.requests)
            assertEmpty(fixture.previousEvents)
            assertTrue(notificationText(fixture.notifications.single()).contains("Generated SQL"))
        }
    }

    fun testLaterInvalidPipePreflightsBeforePlainPrefixExecution() {
        val sql = "SELECT 1; FROM broken |> WHERE;"
        ExecutionFixture(sql).use { fixture ->
            fixture.executeScript()
            assertEmpty(fixture.requests)
            assertEquals(NotificationType.ERROR, fixture.notifications.single().type)
        }
    }

    fun testSelectionScriptScopeTransformsEachSelectedStatement() {
        val sql = "$safeProgram; SELECT 2;"
        ExecutionFixture(sql).use { fixture ->
            fixture.executeScript()
            while (fixture.requests.size < 2) (fixture.requests.last() as ConsoleDataRequest).onFinished()
            val pipe = fixture.requests[0] as DataRequest.QueryRequest
            assertFalse(pipe.query, "|>" in pipe.query)
            assertEquals("SELECT 2", (fixture.requests[1] as DataRequest.QueryRequest).query.trim())
            assertTrue(fixture.requests.all { it.owner === fixture.console })
        }
    }

    fun testArraySliceColonIsNotSubstitutedAsAParameter() {
        val sql = "FROM offline_rows |> SELECT arr[1:end_idx] AS sliced, arr[1 + :offset] AS item"
        ExecutionFixture(sql).use { fixture ->
            ShowSqlParametersPanelAction.getStorage(fixture.console).putValue("end_idx", "5")
            ShowSqlParametersPanelAction.getStorage(fixture.console).putValue("offset", "5")
            fixture.editor.selectionModel.setSelection(0, sql.length)
            fixture.execute()
            val query = (fixture.requests.single() as DataRequest.QueryRequest).query
            assertTrue(query, "1:end_idx" in query)
            assertFalse(query, "arr[15]" in query)
            assertFalse(query, ":offset" in query)
        }
    }

    fun testUnselectedMalformedPipeTailsDoNotExecuteValidPrefixes() {
        for (sql in listOf(
            "$safeProgram |> WHERE", "$safeProgram /* unfinished; SELECT 1",
            "$safeProgram |> WHERE id = 'unfinished; SELECT 1",
            "$safeProgram /*+ unfinished; SELECT 1",
            "$safeProgram /* outer /* inner */ unfinished; SELECT 1",
            "$safeProgram |> SELECT \$\$unfinished; SELECT 1",
            "$safeProgram |> SELECT `unfinished; SELECT 1",
        )) {
            ExecutionFixture(sql).use { fixture ->
                var reports = 0
                for (variant in 1..4) {
                    for (caret in listOf(0, sql.length / 2, sql.length)) {
                        fixture.editor.caretModel.moveToOffset(caret)
                        assertFalse(fixture.editor.selectionModel.hasSelection())
                        fixture.execute(variant)
                        assertEmpty("caret=$caret $sql", fixture.requests)
                        assertEmpty(fixture.previousEvents)
                        assertEquals(++reports, fixture.notifications.size)
                        assertEquals(NotificationType.ERROR, fixture.notifications.last().type)
                        assertEquals(sql, fixture.editor.document.text)
                    }
                }
            }
        }
    }

    fun testLoweringCannotProduceMismatchedOrUnapprovedPayloads() {
        val safe = DorisPipesEngine.transpile(safeProgram) as PipeTranslation.Lowered
        val lossy = DorisPipesEngine.transpile(warningPrograms.first()) as PipeTranslation.Lowered
        ExecutionFixture(safeProgram).use { fixture ->
            val submissionThread = Thread.currentThread()
            val submissionCalls = mutableListOf<String>()
            // The IDE may query getProject on the session from background readers. This guard
            // must reject synchronously before the submitting thread accesses the session.
            fixture.recording.beforeSessionCall = { name ->
                if (Thread.currentThread() === submissionThread) submissionCalls.add(name)
            }
            submissionCalls.clear()
            assertNotNull(lossy.blocker)
            assertEquals(safe.result.sql, safe.sql)
            assertSame(safe.sql, safe.result.sourceMap!!.output)
            assertTrue(DorisPipesEngine.transpile("SHOW FRONTENDS |> LIMIT 1") is PipeTranslation.Refused)
            assertEmpty(submissionCalls)
            assertEmpty(fixture.requests)
            assertEmpty(fixture.previousEvents)
        }
    }

    fun testDestinationChangedBySubmitListenerIsRefused() {
        ExecutionFixture(safeProgram).use { fixture ->
            fixture.beforeSubmit = { fixture.point.url = "jdbc:offline:another-doris" }
            fixture.execute()
            assertEmpty(fixture.requests)
            assertEmpty(fixture.previousEvents)
            assertEquals(NotificationType.ERROR, fixture.notifications.single().type)
            assertTrue(notificationText(fixture.notifications.single()).contains("destination changed"))
        }
    }

    fun testRunToStageIntentionAndMenuBlockLossyPrefixesInTheRealConsole() {
        ExecutionFixture(warningPrograms.first()).use { fixture ->
            for (sql in warningPrograms) {
                fixture.replaceSql(sql)
                fixture.editor.caretModel.moveToOffset(sql.length - 1)
                assertSame(fixture.console, JdbcConsoleProvider.getValidConsole(project, fixture.file.viewProvider.virtualFile))
                for (menu in listOf(false, true)) {
                    fixture.runToStage(menu)
                    assertEmpty(fixture.requests)
                    assertEmpty(fixture.previousEvents)
                    val notification = fixture.notifications.last()
                    assertEquals(NotificationType.ERROR, notification.type)
                    assertEquals("Doris Pipes", notification.title)
                    val translation = DorisPipesEngine.transpile(sql) as PipeTranslation.Lowered
                    for (warning in translation.unsupportedMessages) assertTrue(notificationText(notification), warning in notificationText(notification))
                    assertEquals(sql, fixture.editor.document.text)
                }
                assertEquals(2, fixture.notifications.size)
                fixture.notifications.forEach { it.expire() }
                fixture.notifications.clear()
            }
        }
    }

    fun testRunToStageUsesConsoleParameterStorage() {
        val sql = "FROM offline_rows |> WHERE id = :id |> SELECT id"
        // Reproduce the shared worker's already-cached data-source registry in an isolated run too.
        val facade = DbPsiFacade.getInstance(project)
        val sourceIdsBefore = facade.dataSources.map { it.uniqueId }
        val fixture = ExecutionFixture(sql)
        fixture.use {
            // Console lookup disposes clients whose target is missing from the PSI registry.
            assertContainsElements(JdbcConsole.getActiveConsoles(project), fixture.console)
            ShowSqlParametersPanelAction.getStorage(fixture.console).putValue("id", "23")
            fixture.editor.caretModel.moveToOffset(sql.indexOf("WHERE") + 2)
            fixture.runToStage(menu = false)
            val request = fixture.requests.single() as DataRequest.QueryRequest
            assertTrue(request.query, "23" in request.query)
            assertFalse(request.query, ":id" in request.query || "|>" in request.query)
            assertSame(fixture.console, request.owner)
        }
        assertEquals(sourceIdsBefore, facade.dataSources.map { it.uniqueId })
        assertTrue(Disposer.isDisposed(fixture.console))
        assertFalse(JdbcConsole.getActiveConsoles(project).contains(fixture.console))
    }

    fun testDestinationChangesDuringParameterPromptingSubmitNothing() {
        val sql = "FROM offline_rows |> WHERE id = :id |> SELECT id"
        val changes = listOf<(ExecutionFixture) -> Unit>(
            { it.point.url = "jdbc:offline:another-doris" },
            { settings.enabled = false },
        )
        for (runToStage in listOf(false, true)) {
            assertEquals(1, runWithParameterPrompt(sql, runToStage) {}.size)
            for (change in changes) {
                try {
                    assertEmpty(runWithParameterPrompt(sql, runToStage, change))
                } finally {
                    settings.enabled = true
                }
            }
        }
    }

    /** Answers the real parameter dialog, applying [duringPrompt] first; returns the submitted requests. */
    private fun runWithParameterPrompt(sql: String, runToStage: Boolean, duringPrompt: (ExecutionFixture) -> Unit): List<GridDataRequest> =
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("WHERE"))
            var prompted = false
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) {
                    prompted = true
                    ShowSqlParametersPanelAction.getStorage(fixture.console).putValue("id", "7")
                    duringPrompt(fixture)
                    component.close(DialogWrapper.OK_EXIT_CODE)
                }
            })
            if (runToStage) fixture.runToStage(menu = false) else fixture.execute()
            assertTrue(prompted)
            if (fixture.requests.isEmpty()) assertEquals(NotificationType.ERROR, fixture.notifications.last().type)
            assertEmpty(fixture.previousEvents)
            fixture.requests.toList()
        }

    fun testSharedSessionSubmissionAndConsoleLookupUseExactFileOwner() {
        ExecutionFixture(safeProgram).use { fixture ->
            val secondFile = myFixture.addFileToProject("second-owner.sql", safeProgram)
            setSqlDialectMapping(project, secondFile.virtualFile, DorisSqlDialect.INSTANCE)
            val second = JdbcConsole.newConsole(project).forFile(secondFile.virtualFile)
                .fromDataSource(fixture.point).useSession(fixture.session).build()
            try {
                assertEquals(listOf(fixture.console, second), fixture.clients)
                assertSame(second, JdbcConsoleProvider.getValidConsole(project, secondFile.viewProvider.virtualFile))
                myFixture.configureFromExistingVirtualFile(secondFile.virtualFile)
                val editor = myFixture.editor
                editor.selectionModel.setSelection(0, editor.document.textLength)
                val event = AnActionEvent.createFromDataContext("Shared session", Presentation(), SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, secondFile)
                    .add(CommonDataKeys.VIRTUAL_FILE, secondFile.virtualFile).add(CommonDataKeys.EDITOR, editor)
                    .add(SessionClientHolder.CLIENT_KEY, second).build())
                DorisPipesRunQueryAction(1, object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) = fail("Shared-session PIPE must not delegate")
                }).actionPerformed(event)
                assertSame(second, (fixture.requests.single() as DataRequest.QueryRequest).owner)
            } finally {
                com.intellij.database.console.session.DatabaseSessionViewService.getInstance().destroyView(second)
                Disposer.dispose(second)
                setSqlDialectMapping(project, secondFile.virtualFile, null)
            }
        }
    }

    fun testRunToStageExecutesOnlyTheSafePrefixBeforeALaterLossyStage() {
        val prefix = "FROM offline_rows |> WHERE id > 0"
        val sql = "$prefix |> SELECT LAST_DAY(d, YEAR) AS period_end"
        assertNotNull((DorisPipesEngine.transpile(sql) as PipeTranslation.Lowered).executionError)
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("WHERE") + 2)
            assertSame(fixture.console, JdbcConsoleProvider.getValidConsole(project, fixture.file.viewProvider.virtualFile))
            for (menu in listOf(false, true)) {
                fixture.runToStage(menu)
                val request = fixture.requests.last() as DataRequest.QueryRequest
                assertEquals((DorisPipesEngine.transpile(prefix) as PipeTranslation.Lowered).dorisSql, request.query)
                assertFalse(request.query, "LAST_DAY" in request.query)
                assertEquals(TextRange(0, prefix.length), (request as DataRequest.CoupledWithEditor).range)
                assertSame(fixture.console, request.owner)
                assertEmpty(fixture.notifications)
                assertEmpty(fixture.previousEvents)
                assertEquals(sql, fixture.editor.document.text)
            }
            assertEquals(2, fixture.requests.size)
            assertEmpty(fixture.notifications)
        }
    }

    fun testRunToStageExecutesTheInitialFromStageOnly() {
        val prefix = "FROM offline_rows"
        val sql = "$prefix |> WHERE id > 0 |> SELECT id"
        val translated = DorisPipesEngine.transpile(
            prefix,
            allowNamedParameters = true,
            allowFirstFromStage = true,
        ) as PipeTranslation.Lowered
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("offline_rows"))
            for (menu in listOf(false, true)) {
                fixture.runToStage(menu)
                val request = fixture.requests.last() as DataRequest.QueryRequest
                assertEquals(translated.dorisSql, request.query)
                assertFalse(request.query, "WHERE" in request.query || "|>" in request.query)
                assertEquals(TextRange(0, prefix.length), (request as DataRequest.CoupledWithEditor).range)
                assertSame(fixture.console, request.owner)
                assertEquals(sql, fixture.editor.document.text)
            }
            assertEquals(2, fixture.requests.size)
            assertEmpty(fixture.previousEvents)
        }
    }

    fun testRunToStageRefusesNestedPipelinesAndSingleStagePrograms() {
        val nested = "FROM offline_rows |> WHERE id IN (FROM other_rows |> WHERE id > 0 |> SELECT id) |> SELECT id"
        val nestedOnly = "SELECT id FROM (FROM offline_rows |> WHERE id > 0) s"
        val refusals = listOf(
            nested to nested.indexOf("WHERE id > 0"),
            nestedOnly to nestedOnly.indexOf("SELECT id"),
        )
        ExecutionFixture(nested).use { fixture ->
            for ((sql, caret) in refusals) {
                fixture.replaceSql(sql)
                fixture.editor.selectionModel.removeSelection()
                fixture.editor.caretModel.moveToOffset(caret)
                for (menu in listOf(false, true)) fixture.runToStage(menu)
                assertEmpty(fixture.requests)
            }

            fixture.replaceSql(nested)
            fixture.editor.selectionModel.removeSelection()
            fixture.editor.caretModel.moveToOffset(nested.lastIndexOf("SELECT"))
            fixture.runToStage(menu = false)
            val request = fixture.requests.single() as DataRequest.QueryRequest
            assertEquals((DorisPipesEngine.transpile(nested) as PipeTranslation.Lowered).dorisSql, request.query)
            assertEmpty(fixture.previousEvents)
        }
    }

    fun testPreviewShowsReadOnlySqlAndEveryWarningAsSeparatePlainText() {
        val extraWarnings = listOf("first line\nsecond line", "<b>not HTML</b> & <script>not executable</script>")
        val diagnosticDialect = object : PipeDialect by DorisPipeDialect {
            override val validator = object : NativeSqlValidator {
                override val timing = ValidationTiming.Lowering
                override fun validate(sql: String, file: com.intellij.psi.PsiFile?) =
                    NativeVerdict.Rejected(extraWarnings.joinToString("\n"))
            }
        }
        for ((program, dialect) in listOf(warningPrograms.first() to DorisPipeDialect, safeProgram to diagnosticDialect)) {
            ExecutionFixture(program).use { fixture ->
                val result = dialect.lower(program) as PipeTranslation.Lowered
                fixture.editor.selectionModel.setSelection(2, 8)
                val original = fixture.editor.document.text
                val stamp = fixture.editor.document.modificationStamp
                val popup = preview(fixture, dialect)
                try {
                    val components = components(popup.content).toList()
                    val sql = components.filterIsInstance<LanguageTextField>().single()
                    sql.setDisposedWith(testRootDisposable)
                    assertTrue(sql.isViewer)
                    assertEquals(result.sql, sql.text)
                    val previewEditor = checkNotNull(sql.getEditor(true))
                    assertTrue(previewEditor.isViewer)
                    assertEquals(result.sql, previewEditor.document.text)
                    val sqlScroll = components.filterIsInstance<JScrollPane>().single { it.viewport.view === sql }
                    assertSame(sql, sqlScroll.viewport.view)
                    val warnings = components.filterIsInstance<JTextArea>().single()
                    assertFalse(warnings.isEditable)
                    assertTrue(warnings.lineWrap)
                    assertEquals(result.blocker, warnings.text)
                    for (warning in if (dialect === diagnosticDialect) extraWarnings else result.unsupportedMessages) {
                        assertTrue(warnings.text, warning in warnings.text)
                        assertFalse(sql.text, warning in sql.text)
                    }
                    assertEquals("Copy", components.filterIsInstance<javax.swing.JButton>().single().text)
                    assertEquals(original, fixture.editor.document.text)
                    assertEquals(stamp, fixture.editor.document.modificationStamp)
                    assertEquals(2, fixture.editor.selectionModel.selectionStart)
                    assertEquals(8, fixture.editor.selectionModel.selectionEnd)
                    assertEmpty(fixture.requests)
                    assertEmpty(fixture.previousEvents)
                    assertEmpty(fixture.notifications)
                } finally { popup.cancel() }
            }
        }
    }

    private fun components(component: java.awt.Component): Sequence<java.awt.Component> = sequence {
        yield(component)
        if (component is java.awt.Container) for (child in component.components) yieldAll(components(child))
    }

    private fun preview(fixture: ExecutionFixture, dialect: PipeDialect): JBPopup {
        var popup: JBPopup? = null
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<JBPopup>(JBPopup::class.java) {
            override fun doIntercept(component: JBPopup) { popup = component }
        })
        try {
            object : PipePreviewIntention(dialect) {}.invoke(project, fixture.editor, fixture.file)
        } finally {
            if (popup == null) UiInterceptors.clear()
        }
        return checkNotNull(popup)
    }

    fun testExecutionNotificationPreservesMarkupLikeAndMultilineDiagnostics() {
        ExecutionFixture("FROM offline_rows |> SELECT id").use { fixture ->
            val message = "first <b>literal</b> & text\r\nsecond\rthird <script>still text</script>"
            notifyEscaped(project, "Doris Pipes", "PIPE translation failed", message, NotificationType.ERROR)
            val notification = fixture.notifications.single()
            assertEquals("first &lt;b&gt;literal&lt;/b&gt; &amp; text<br>second<br>third &lt;script&gt;still text&lt;/script&gt;", notification.content)
            assertEquals(StringUtil.convertLineSeparators(message), notificationText(notification))
            assertEmpty(fixture.requests)
            assertEmpty(fixture.previousEvents)
        }
    }

    fun testStructureViewEditorFallbackPreservesThePipeCandidate() {
        val sql = "FROM t |> SELECT * -- ;\n|> WHERE tenant_id = 7;"
        val candidates = mutableListOf<String>()
        val dialect = object : PipeDialect by DorisPipeDialect {
            override fun scriptRanges(text: String) = DorisPipeDialect.scriptRanges(text).also { candidates += text }
        }
        ExecutionFixture(sql, dialect = dialect).use { fixture ->
            val event = AnActionEvent.createFromDataContext("StructureViewPopup", Presentation(), SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, fixture.file)
                .add(CommonDataKeys.VIRTUAL_FILE, fixture.file.virtualFile)
                .add(com.intellij.openapi.actionSystem.PlatformCoreDataKeys.FILE_EDITOR,
                    com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(fixture.editor))
                .add(SessionClientHolder.CLIENT_KEY, fixture.console).build())
            assertNull(event.getData(CommonDataKeys.EDITOR))
            val resolved = (event.getData(com.intellij.openapi.actionSystem.PlatformCoreDataKeys.FILE_EDITOR)
                as? com.intellij.openapi.fileEditor.TextEditor)?.editor
            assertSame(fixture.editor, resolved)
            assertEquals(sql, DorisPipes.chunkAt(resolved!!.document.text, resolved.caretModel.offset)!!.text)
            fixture.action().actionPerformed(event)
            assertTrue(candidates.isNotEmpty())
            assertEquals(setOf(sql), candidates.toSet())
            assertEquals(sql, fixture.editor.document.text)
            assertEmpty(fixture.previousEvents)
        }
    }

    fun testEngineSafetyRefusalReportsWithoutSubmittingOrDelegating() {
        val text = "FROM t AS a |> FULL OUTER JOIN u AS b ON a.id = b.id |> LIMIT 2 |> SELECT a.id"
        ExecutionFixture(text).use { fixture ->
            for (variant in 1..4) {
                fixture.execute(variant)
                assertTrue(notificationText(fixture.notifications.last()).contains("Cannot preserve a pipe SELECT/DISTINCT input boundary"))
            }
            assertEquals(4, fixture.notifications.size)
            assertEmpty(fixture.requests)
            assertEmpty(fixture.previousEvents)
            fixture.replaceSql(safeProgram)
            fixture.execute()
            assertEquals(1, fixture.requests.size)
            assertEquals(4, fixture.notifications.size)
            fixture.replaceSql("SELECT 1")
            val event = fixture.execute()
            assertEquals(listOf(event), fixture.previousEvents)
        }
    }

    fun testWarningGatePreservesSqlMessagesAndSourceMapForPreview() {
        val text = "FROM t |> SELECT LAST_DAY(d, YEAR) AS period_end |> LIMIT 2"
        val result = DorisPipesEngine.transpile(text) as PipeTranslation.Lowered
        val raw = result.result
        val sql = result.sql
        val warnings = raw.unsupportedMessages.toList()
        assertFalse(warnings.isEmpty())
        ExecutionFixture(text).use { fixture ->
            fixture.execute()
            assertEquals(1, fixture.notifications.size)
            assertEmpty(fixture.requests)
            for (warning in warnings) assertTrue(notificationText(fixture.notifications.single()).contains(warning))
            assertSame(raw, result.result)
            assertSame(sql, result.sql)
            assertSame(sql, raw.sourceMap!!.output)
            assertEquals(warnings, raw.unsupportedMessages)
            assertTrue(DorisPipesEngine.pipeSyntaxErrors(text).single().message.contains(warnings.single()))
            val safeText = "FROM t |> SELECT LAST_DAY(d) AS period_end |> LIMIT 2"
            val safe = DorisPipesEngine.transpile(safeText) as PipeTranslation.Lowered
            assertNull(safe.blocker)
            fixture.replaceSql(safeText)
            fixture.execute()
            assertEquals(1, fixture.requests.size)
            assertEquals(1, fixture.notifications.size)
            assertEquals(warnings, result.result.unsupportedMessages)
        }
    }

    fun testAllUnsupportedDiagnosticsBlockIncludingEmptyOrMarkupLikeMessages() {
        val safe = DorisPipesEngine.transpile(safeProgram) as PipeTranslation.Lowered
        for (messages in listOf(listOf(""), listOf("first\nsecond", "<html>&literal</html>"))) {
            val result = safe.copy(result = safe.result.copy(unsupportedMessages = messages))
            assertNotNull(result.blocker)
            for (message in messages) assertTrue(result.blocker!!.contains(message))
            val dialect = object : PipeDialect by DorisPipeDialect {
                override val validator = object : NativeSqlValidator {
                    override val timing = ValidationTiming.Lowering
                    override fun validate(sql: String, file: com.intellij.psi.PsiFile?) = NativeVerdict.Rejected(messages.joinToString("\n"))
                }
            }
            ExecutionFixture(safeProgram, dialect = dialect).use { fixture ->
                fixture.execute()
                assertEmpty(fixture.requests)
                assertEmpty(fixture.previousEvents)
                assertEquals(NotificationType.ERROR, fixture.notifications.single().type)
                for (message in messages) assertTrue(notificationText(fixture.notifications.single()).contains(message))
            }
            assertEquals(messages, result.result.unsupportedMessages)
        }
    }

    fun testFailedSubmissionIsHandledAndNeverDelegatesAcrossAllVariants() {
        for (variant in 1..4) ExecutionFixture(safeProgram).use { fixture ->
            fixture.session.detach(fixture.console)
            fixture.execute(variant)
            assertEquals(1, fixture.runs.size)
            assertEquals(NotificationType.ERROR, fixture.notifications.single().type)
            assertEmpty(fixture.requests)
            assertEmpty(fixture.previousEvents)
        }
    }

    fun testTranslationNotificationAndSubmissionExceptionsNeverGrantRawFallback() {
        for (failure in listOf(IllegalStateException("injected failure"), com.intellij.openapi.progress.ProcessCanceledException(),
            java.util.concurrent.CancellationException("cancelled"))) {
            for (site in listOf("translation", "notification", "submission")) for (variant in 1..4) {
                var reports = 0
                var submissions = 0
                val dialect = object : PipeDialect by DorisPipeDialect {
                    override val notificationGroup: String get() {
                        if (site == "notification") { reports++; throw failure }
                        return DorisPipeDialect.notificationGroup
                    }
                    override val validator = object : NativeSqlValidator {
                        override val timing = ValidationTiming.Lowering
                        override fun validate(sql: String, file: com.intellij.psi.PsiFile?): NativeVerdict {
                            if (site == "translation") throw failure
                            return NativeVerdict.Accepted
                        }
                    }
                }
                ExecutionFixture(if (site == "notification") warningPrograms.first() else safeProgram, dialect = dialect).use { fixture ->
                    fixture.recording.onSubmit = { submissions++; throw failure }
                    val actual = runCatching { fixture.execute(variant) }.exceptionOrNull()
                    assertSame("Expected $site failure through variant $variant", failure, actual)
                    assertEmpty(fixture.previousEvents)
                    assertEmpty(fixture.notifications)
                    assertEquals(if (site == "notification") 1 else 0, reports)
                    assertEquals(if (site == "submission") 1 else 0, submissions)
                }
            }
        }
    }

    private fun notificationText(notification: Notification): String =
        StringUtil.unescapeXmlEntities(notification.content.replace("<br>", "\n"))

    fun testVendoredLexerDistinguishesOperatorTokensFromQuotedMarkers() {
        fun tokens(text: String) = DorisSqlParser().newLexer(text).allTokens
            .filter { it.channel == Token.DEFAULT_CHANNEL }
            .map { it.type to it.text }

        // The vendored Doris grammar has no single PIPE operator token for |>: adjacency matters.
        assertEquals(listOf(DorisLexer.PIPE to "|", DorisLexer.GT to ">"), tokens("|>"))
        assertEquals(listOf(DorisLexer.STRING_LITERAL to "'|>'"), tokens("'|>'"))
        assertEquals(listOf(DorisLexer.STRING_LITERAL to "\"|>\""), tokens("\"|>\""))
        assertEquals(listOf(DorisLexer.BACKQUOTED_IDENTIFIER to "`|>`"), tokens("`|>`"))
        assertEquals(listOf(DorisLexer.DOLLAR_QUOTED_STRING to "\$\$|>\$\$"), tokens("\$\$|>\$\$"))
        assertEmpty(tokens("-- |>\n/* |> */"))
        for (text in listOf("|>", "| >", "|/* hidden */>")) {
            val operators = DorisSqlParser().newLexer(text).allTokens.filter { it.channel == Token.DEFAULT_CHANNEL }
            assertEquals(listOf(DorisLexer.PIPE, DorisLexer.GT), operators.map { it.type })
            assertEquals(text == "|>", operators[0].stopIndex + 1 == operators[1].startIndex)
        }
    }

    private inner class ExecutionFixture(sql: String, driverId: String = "doris", dialect: PipeDialect = DorisPipeDialect) : AutoCloseable {
        private val virtualFile = myFixture.configureByText("offline-execution-${UUID.randomUUID()}.sql", sql).virtualFile
        // Setting the dialect invalidates the original generic-SQL PSI file.
        val file get() = checkNotNull(PsiManager.getInstance(project).findFile(virtualFile))
        val editor = myFixture.editor
        val requests get() = recording.requests
        val previousEvents = mutableListOf<AnActionEvent>()
        val runs = mutableListOf<PipeRun>()
        var beforeSubmit: (PipeRun) -> Unit = {}
        val clients get() = session.clients.toList()
        val notifications = mutableListOf<Notification>()
        private val notificationConnection = project.messageBus.connect()
        val recording: RecordingConsole
        val console get() = recording.console
        val point get() = console.dataSource
        val session get() = console.session
        private val previous = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) { previousEvents.add(e) }
        }
        private val interceptor = PipeExecuteInterceptor(object : PipeDialect by dialect {
            override val submitListener = object : PipeSubmitListener {
                override fun beforeSubmit(run: PipeRun) {
                    runs += run
                    DorisPipesExecution.beforeSubmit(run)
                    this@ExecutionFixture.beforeSubmit(run)
                }

                override fun afterSubmit(run: PipeRun) = DorisPipesExecution.afterSubmit(run)
            }
        })

        init {
            setSqlDialectMapping(project, virtualFile, if (driverId == "doris") DorisSqlDialect.INSTANCE else null)
            recording = RecordingConsole(project, virtualFile, driverId)
            try {
                val facade = DbPsiFacade.getInstance(project)
                assertNotNull("New source must be visible to console validity checks", facade.findDataSource(point.uniqueId))
                assertSame(if (driverId == "doris") DorisDbms.DORIS else Dbms.MYSQL, point.dbms)
                assertTrue("The real console must be valid, not merely present in the event", console.isValid)
                assertSame(session, console.session)
                if (driverId == "doris") assertSame(DorisSqlDialect.INSTANCE, file.language)
                else assertFalse(file.language.isKindOf(DorisSqlDialect.INSTANCE))
                assertEquals(listOf(console), clients)
                assertEmpty(requests)
                notificationConnection.subscribe(Notifications.TOPIC, object : Notifications {
                    override fun notify(notification: Notification) {
                        if (notification.groupId == "Doris Pipes") {
                            notification.setSuppressShowingPopup(true)
                            notifications.add(notification)
                        }
                    }
                })
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun replaceSql(sql: String) {
            WriteCommandAction.runWriteCommandAction(project) { editor.document.setText(sql) }
            // Direct fixture invocation must honor the wrapper's committed-document policy.
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            editor.selectionModel.setSelection(0, sql.length)
        }

        fun runToStage(menu: Boolean) {
            if (menu) {
                val action = RunPipesToStageAction()
                val e = event()
                action.update(e)
                assertTrue(e.presentation.isEnabledAndVisible)
                action.actionPerformed(e)
            } else {
                val intention = RunPipesToStageIntention()
                assertTrue(intention.isAvailable(project, editor, file))
                intention.invoke(project, editor, file)
            }
        }

        fun event(): AnActionEvent = AnActionEvent.createFromDataContext(
            "DorisPipesExecutionTest", Presentation(), SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.PSI_FILE, file)
                .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                .add(SessionClientHolder.CLIENT_KEY, console)
                .build(),
        )

        fun action(variant: Int = 1): DorisPipesRunQueryAction {
            val handle: (AnActionEvent, DatabaseSettings.ExecOption) -> Boolean = { event, option ->
                interceptor.execute(event, option) == ExecutionClaim.HANDLED
            }
            return if (variant == 4) DorisPipesRunSelectionAction(previous, handle)
            else DorisPipesRunQueryAction(variant, previous, handle)
        }

        fun executeScript() {
            editor.selectionModel.setSelection(0, editor.document.textLength)
            val settings = DatabaseSettings.getSettings()
            val old = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execSelection = DatabaseSettings.EXECUTE_SELECTION_EXACTLY_SCRIPT
            }
            try { execute() } finally { settings.execOptions[0] = old }
        }

        fun execute(variant: Int = 1): AnActionEvent = event().also { event ->
            assertSame(console, JdbcConsole.findConsole(event))
            action(variant).actionPerformed(event)
        }

        override fun close() {
            notificationConnection.disconnect()
            notifications.forEach { it.expire() }
            try {
                recording.close()
            } finally {
                setSqlDialectMapping(project, virtualFile, null)
            }
        }
    }

    private companion object {
        const val safeProgram = "FROM offline_rows |> SELECT id"
        val warningPrograms = listOf("YEAR", "QUARTER", "WEEK").map {
            "FROM offline_rows |> SELECT LAST_DAY(d, $it) AS period_end"
        } + "FROM offline_rows |> AGGREGATE APPROX_COUNT_DISTINCT(user_id, 0.01) AS users"
    }

    private fun <E> cachedQueryModel(delegate: ScriptModel<E>, cached: String): ScriptModel<E> = object : ScriptModel<E>() {
        override fun isActual() = delegate.isActual
        override fun subModel(range: TextRange?) = cachedQueryModel(delegate.subModel(range), cached)
        override fun everything() = delegate.everything()
        override fun statements() = delegate.statements().transform { statement ->
            object : ScriptModel.StatementIt<E> by statement { override fun query() = cached }
        }
        override fun parameters() = delegate.parameters()
        override fun externals() = delegate.externals()
        override fun getVirtualFile() = delegate.virtualFile
        override fun getTextRange() = delegate.textRange
        override fun getLanguage() = delegate.language
        override fun <EE : Any?> rawTransform(transform: com.intellij.util.Function<in com.intellij.psi.SyntaxTraverser<E>, out com.intellij.psi.SyntaxTraverser<EE>>): ScriptModel<EE> =
            cachedQueryModel(delegate.rawTransform(transform), cached)
    }
}
