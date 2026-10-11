package dev.sort.doris.pipes

import com.intellij.database.DataBus
import com.intellij.database.Dbms
import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.client.DatabaseSessionClient
import com.intellij.database.console.client.DatabaseSessionClientWithFile
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataConsumer
import com.intellij.database.datagrid.DataProducer
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
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.LanguageTextField
import com.intellij.ui.UiInterceptors
import dev.brikk.house.sql.intellij.SqlExecutionRefusal
import dev.sort.doris.DorisDbms
import dev.sort.doris.setSqlDialectMapping
import dev.sort.doris.sql.DorisSqlDialect
import org.antlr.v4.runtime.Token
import org.apache.doris.nereids.DorisLexer
import org.apache.doris.sqlparser.DorisSqlParser
import java.lang.reflect.Proxy
import java.awt.BorderLayout
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
        val translated = DorisPipesEngine.transpile(sql) as DorisPipesEngine.Transpile.Ok
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
            assertEquals((DorisPipesEngine.transpile(sql) as DorisPipesEngine.Transpile.Ok).dorisSql, request.query)
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
                (DorisPipesEngine.transpile(original) as DorisPipesEngine.Transpile.Ok).dorisSql,
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
                (DorisPipesEngine.transpile(other) as DorisPipesEngine.Transpile.Ok).dorisSql,
                (fixture.requests.last() as DataRequest.QueryRequest).query,
            )

            fixture.editor.selectionModel.setSelection(0, updated.length)
            fixture.editor.caretModel.moveToOffset(updated.indexOf("id = 9"))
            fixture.execute()
            assertEquals(
                (DorisPipesEngine.transpile(updated) as DorisPipesEngine.Transpile.Ok).dorisSql,
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
                val model = PipeScriptModel(cachedQueryModel(fixture.console.scriptModel, cached), fixture.editor)
                assertNull(model.finalizeTranslations(fixture.console.pStorage))
                assertEquals(current, model.plans.single().sourceQuery)
                assertEquals((DorisPipesEngine.transpile(current) as DorisPipesEngine.Transpile.Ok).dorisSql,
                    model.plans.single().dorisSql)
            }
            fixture.replaceSql("SELECT 1")
            val failure = runCatching {
                PipeScriptModel(cachedQueryModel(fixture.console.scriptModel, old), fixture.editor)
            }.exceptionOrNull()
            assertTrue(failure is PipeTranslationFailure)
            assertTrue((failure as PipeTranslationFailure).error.message.contains("prepared statement changed"))
        }
    }

    fun testPreparedModelRejectsEditAndRevertBeforeSubmission() {
        ExecutionFixture(safeProgram).use { fixture ->
            val model = PipeScriptModel(fixture.console.scriptModel, fixture.editor)
            WriteCommandAction.runWriteCommandAction(project) {
                fixture.editor.document.insertString(0, " ")
                fixture.editor.document.deleteString(0, 1)
            }
            assertFalse(model.isActual)
            val failure = runCatching { model.finalizeTranslations(fixture.console.pStorage) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalStateException)
            assertTrue(failure!!.message!!, failure.message!!.contains("SQL changed during preparation"))
            assertEmpty(fixture.requests)
        }
    }

    fun testEditDuringTrackingStopsBeforeAnyMixedScriptRequest() {
        val sql = "SELECT 1; $safeProgram;"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("FROM"))
            var editedAtTracking = false
            fixture.onAddAuditor = { auditor ->
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
                assertTrue(failure.toString(), failure is IllegalStateException)
                assertTrue(failure!!.message!!, failure.message!!.contains("SQL changed during preparation"))
                assertEmpty(fixture.requests)
                assertEmpty(fixture.previousEvents)
            } finally { settings.execOptions[0] = old }
        }
    }

    fun testCancellationDuringTrackingNeverSubmitsOrDelegates() {
        ExecutionFixture(safeProgram).use { fixture ->
            val canceled = com.intellij.openapi.progress.ProcessCanceledException()
            var canceledAtTracking = false
            fixture.onAddAuditor = { auditor ->
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
                assertTrue(failure.toString(), failure is IllegalStateException)
                assertTrue(failure!!.message!!, failure.message!!.contains("SQL changed during preparation"))
                assertEmpty(fixture.requests)
                assertEmpty(fixture.previousEvents)
                assertEquals(safeProgram, fixture.editor.document.text)
            } finally { connection.disconnect() }
        }
    }

    fun testAuthoredMapPreservesUnicodeAndMixedScriptSlots() {
        val pipe = "FROM offline_rows |> SELECT '😀' AS marker, id |> WHERE id > 0"
        ExecutionFixture("SELECT 1; $pipe; SELECT 2;").use { fixture ->
            val model = PipeScriptModel(fixture.console.scriptModel, fixture.editor)
            assertNull(model.finalizeTranslations(fixture.console.pStorage))
            assertEquals(3, model.executionPlans.size)
            assertNull(model.executionPlans.first())
            assertNull(model.executionPlans.last())
            val plan = model.plans.single()
            assertNotNull(plan.authoredMap)
            val outputOffset = plan.dorisSql.indexOf("id")
            val prefix = plan.dorisSql.substring(0, outputOffset)
            val line = 1 + prefix.count { it == '\n' }
            val linePrefix = prefix.substringAfterLast('\n')
            val pos = linePrefix.codePointCount(0, linePrefix.length)
            val mapped = mappedPipeError("(line $line, pos $pos)", plan.sourceQuery, plan.authoredMap)!!
            assertEquals("id", plan.sourceQuery.substring(mapped.startOffset!!, mapped.endOffset!! + 1))
            val documentStart = plan.anchor.trimAnchor + mapped.startOffset
            assertEquals("id", fixture.editor.document.text.substring(documentStart, documentStart + 2))
        }
    }

    fun testParameterExpansionWithholdsAuthoredMapInsteadOfGuessing() {
        val sql = "FROM offline_rows |> WHERE id = :id |> SELECT id"
        ExecutionFixture(sql).use { fixture ->
            ShowSqlParametersPanelAction.getStorage(fixture.console).putValue("id", "17000")
            val model = PipeScriptModel(fixture.console.scriptModel, fixture.editor)
            assertContainsElements(model.parameters().map { it.name() }.toList(), "id")
            assertNull(model.finalizeTranslations(fixture.console.pStorage))
            val plan = model.plans.single()
            assertTrue(plan.dorisSql, "17000" in plan.dorisSql)
            assertNull(plan.authoredMap)
            assertNull(mappedPipeError("(line 1, pos 7)", plan.sourceQuery, plan.authoredMap))
            assertEquals(sql, fixture.editor.document.text)
        }
    }

    fun testOutsideStatementWholeScriptStillLowersPipes() {
        val sql = "SELECT 1; $safeProgram; -- caret outside statements"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.length)
            val settings = DatabaseSettings.getSettings()
            val old = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execInside = DatabaseSettings.EXECUTE_INSIDE_SMALLEST
                execOutside = DatabaseSettings.EXECUTE_OUTSIDE_WHOLE_SCRIPT
            }
            try {
                fixture.execute()
                while (fixture.requests.size < 2) (fixture.requests.last() as ConsoleDataRequest).onFinished()
            } finally { settings.execOptions[0] = old }
            assertEquals(2, fixture.requests.size)
            assertFalse((fixture.requests.last() as DataRequest.QueryRequest).query.contains("|>"))
            assertEmpty(fixture.previousEvents)
        }
    }

    fun testSmartSelectionClaimsTheEnclosingPipeline() {
        val sql = "FROM offline_rows |> WHERE id > 0 |> SELECT id"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.selectionModel.setSelection(sql.indexOf("WHERE"), sql.indexOf(" |> SELECT"))
            val settings = DatabaseSettings.getSettings()
            val old = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execSelection = DatabaseSettings.EXECUTE_SELECTION_SMART_EXPAND
            }
            try { fixture.execute() } finally { settings.execOptions[0] = old }
            assertEquals(1, fixture.requests.size)
            assertFalse((fixture.requests.single() as DataRequest.QueryRequest).query.contains("|>"))
            assertEmpty(fixture.previousEvents)
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
                val translation = DorisPipesEngine.transpile(sql) as DorisPipesEngine.Transpile.Ok
                assertNotNull(translation.executionError)
                for (variant in 1..4) {
                    fixture.replaceSql(sql)
                    fixture.execute(variant)
                    assertEquals("warning query must not submit: variant=$variant sql=$sql", 0, fixture.requests.size)
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
                    assertEquals(1, fixture.requests.size)
                    assertEquals((DorisPipesEngine.transpile(safeProgram) as DorisPipesEngine.Transpile.Ok).dorisSql,
                        (fixture.requests.last() as DataRequest.QueryRequest).query)
                    assertEquals("the next query must not inherit warnings", 1, fixture.notifications.size)
                    assertEmpty(fixture.previousEvents)
                    assertEquals(safeProgram, fixture.editor.document.text)
                    fixture.requests.clear()
                    fixture.notifications.forEach { it.expire() }
                    fixture.notifications.clear()
                }
            }
        }
    }

    fun testNativeInvalidGeneratedSqlBlocksEveryVariantWithoutRawFallback() {
        val sql = "SELECT id FROM offline_rows LIMIT -1 |> ORDER BY 1"
        val translation = DorisPipesEngine.transpile(sql) as DorisPipesEngine.Transpile.Ok
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
                assertTrue(notificationText(fixture.notifications.last()).contains("generated SQL"))
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

    fun testSelectionScriptScopeTransformsEachSelectedStatement() {
        val sql = "$safeProgram; SELECT 2;"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.selectionModel.setSelection(0, sql.length)
            val settings = DatabaseSettings.getSettings()
            val old = settings.execOptions[0]
            settings.execOptions[0] = DatabaseSettings.ExecOption().apply {
                execSelection = DatabaseSettings.EXECUTE_SELECTION_EXACTLY_SCRIPT
            }
            try {
                fixture.execute()
                while (fixture.requests.size < 2) (fixture.requests.last() as ConsoleDataRequest).onFinished()
            } finally {
                settings.execOptions[0] = old
            }
            assertFalse((fixture.requests[0] as DataRequest.QueryRequest).query, "|>" in (fixture.requests[0] as DataRequest.QueryRequest).query)
            assertEquals("SELECT 2", (fixture.requests[1] as DataRequest.QueryRequest).query.trim())
            assertTrue(fixture.requests.all { (it as DataRequest).owner === fixture.console })
        }
    }

    fun testLaterInvalidPipePreflightsBeforePlainPrefixExecution() {
        val sql = "SELECT 1; FROM broken |> WHERE;"
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("FROM"))
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
            assertEquals(NotificationType.ERROR, fixture.notifications.single().type)
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
            assertTrue(notificationText(fixture.notifications.single()).contains("generated SQL"))
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

    fun testDetachedClientListStillUsesTheInitiatingConsoleAsOwner() {
        ExecutionFixture(safeProgram).use { fixture ->
            fixture.editor.selectionModel.setSelection(0, safeProgram.length)
            fixture.clients.clear()
            fixture.execute()
            val request = fixture.requests.single() as ConsoleDataRequest
            assertSame(fixture.console, request.owner)
            assertFalse(request.query, "|>" in request.query)
            assertEmpty(fixture.previousEvents)
            assertEmpty(fixture.notifications)
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

    fun testDirectSubmissionRejectsWarningsAndInvalidPayloadsBeforeSessionAccess() {
        val safe = DorisPipesEngine.transpile(safeProgram) as DorisPipesEngine.Transpile.Ok
        val lossy = DorisPipesEngine.transpile(warningPrograms.first()) as DorisPipesEngine.Transpile.Ok
        ExecutionFixture(safeProgram).use { fixture ->
            val destination = PipeDestination(fixture.console)
            val submissionThread = Thread.currentThread()
            val submissionCalls = mutableListOf<String>()
            // The IDE may query getProject on the session from background readers. This guard
            // must reject synchronously before the submitting thread accesses the session.
            fixture.beforeSessionCall = { name ->
                if (Thread.currentThread() === submissionThread) submissionCalls.add(name)
            }
            for (translation in listOf(lossy, DorisPipesEngine.Transpile.Ok("SELECT 1"), safe.copy(dorisSql = "SELECT 2"))) {
                submissionCalls.clear()
                val failure = runCatching { DorisPipesExecution.submit(fixture.console, destination, translation, safeProgram) }.exceptionOrNull()
                assertEquals("expected the submission guard, got $failure", IllegalStateException::class.java, failure?.javaClass)
                assertEmpty(submissionCalls)
                assertEmpty(fixture.requests)
                assertEmpty(fixture.previousEvents)
            }
        }
    }

    fun testDirectSubmissionRefusesAChangedDestination() {
        val translation = DorisPipesEngine.transpile(safeProgram) as DorisPipesEngine.Transpile.Ok
        ExecutionFixture(safeProgram).use { fixture ->
            val destination = PipeDestination(fixture.console)
            fixture.point.url = "jdbc:offline:another-doris"
            val failure = runCatching { DorisPipesExecution.submit(fixture.console, destination, translation, safeProgram) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is SqlExecutionRefusal)
            assertEmpty(fixture.requests)
        }
    }

    fun testRunToStageIntentionAndMenuBlockLossyPrefixesInTheRealConsole() {
        ExecutionFixture(warningPrograms.first()).use { fixture ->
            for (sql in warningPrograms) {
                fixture.replaceSql(sql)
                fixture.editor.caretModel.moveToOffset(sql.length - 1)
                assertSame(fixture.console, DorisPipesUi.consoleFor(project, fixture.file))
                for (menu in listOf(false, true)) {
                    fixture.runToStage(menu)
                    assertEmpty(fixture.requests)
                    assertEmpty(fixture.previousEvents)
                    val notification = fixture.notifications.last()
                    assertEquals(NotificationType.ERROR, notification.type)
                    assertEquals("Stage prefix could not be executed", notification.title)
                    val translation = DorisPipesEngine.transpile(sql) as DorisPipesEngine.Transpile.Ok
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
                assertSame(second, DorisPipesUi.consoleFor(project, secondFile))
                val translation = DorisPipesEngine.transpile(safeProgram) as DorisPipesEngine.Transpile.Ok
                assertTrue(DorisPipesExecution.submit(second, PipeDestination(second), translation, safeProgram))
                assertSame(second, (fixture.requests.single() as DataRequest.QueryRequest).owner)
            } finally {
                Disposer.dispose(second)
                setSqlDialectMapping(project, secondFile.virtualFile, null)
            }
        }
    }

    fun testRunToStageExecutesOnlyTheSafePrefixBeforeALaterLossyStage() {
        val prefix = "FROM offline_rows |> WHERE id > 0"
        val sql = "$prefix |> SELECT LAST_DAY(d, YEAR) AS period_end"
        assertNotNull((DorisPipesEngine.transpile(sql) as DorisPipesEngine.Transpile.Ok).executionError)
        ExecutionFixture(sql).use { fixture ->
            fixture.editor.caretModel.moveToOffset(sql.indexOf("WHERE") + 2)
            assertSame(fixture.console, DorisPipesUi.consoleFor(project, fixture.file))
            for (menu in listOf(false, true)) {
                fixture.runToStage(menu)
                val request = fixture.requests.last() as DataRequest.QueryRequest
                assertEquals((DorisPipesEngine.transpile(prefix) as DorisPipesEngine.Transpile.Ok).dorisSql, request.query)
                assertFalse(request.query, "LAST_DAY" in request.query)
                assertEquals(TextRange(0, prefix.length), (request as DataRequest.CoupledWithEditor).range)
                assertSame(fixture.console, request.owner)
                assertEquals(NotificationType.INFORMATION, fixture.notifications.last().type)
                assertEmpty(fixture.previousEvents)
                assertEquals(sql, fixture.editor.document.text)
            }
            assertEquals(2, fixture.requests.size)
            assertEquals(2, fixture.notifications.size)
        }
    }

    fun testRunToStageExecutesTheInitialFromStageOnly() {
        val prefix = "FROM offline_rows"
        val sql = "$prefix |> WHERE id > 0 |> SELECT id"
        val translated = DorisPipesEngine.transpile(
            prefix,
            allowNamedParameters = true,
            allowFirstFromStage = true,
        ) as DorisPipesEngine.Transpile.Ok
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

    fun testNestedOnlyPipelineExecutesItsLoweredSql() {
        val sql = "SELECT id FROM (FROM offline_rows |> WHERE id > 0) s"
        val translated = DorisPipesEngine.transpile(sql) as DorisPipesEngine.Transpile.Ok
        ExecutionFixture(sql).use { fixture ->
            fixture.execute()
            val request = fixture.requests.single() as DataRequest.QueryRequest
            assertEquals(translated.dorisSql, request.query)
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
            assertEquals((DorisPipesEngine.transpile(nested) as DorisPipesEngine.Transpile.Ok).dorisSql, request.query)
            assertEmpty(fixture.previousEvents)
        }
    }

    fun testPreviewShowsReadOnlySqlAndEveryWarningAsSeparatePlainText() {
        val generated = DorisPipesEngine.transpile(warningPrograms.first()) as DorisPipesEngine.Transpile.Ok
        val extraWarnings = listOf("first line\nsecond line", "<b>not HTML</b> & <script>not executable</script>")
        val result = generated.copy(result = generated.result!!.copy(
            unsupportedMessages = generated.unsupportedMessages + extraWarnings,
        ))
        ExecutionFixture(warningPrograms.first()).use { fixture ->
            fixture.editor.selectionModel.setSelection(2, 8)
            val original = fixture.editor.document.text
            val stamp = fixture.editor.document.modificationStamp
            val (content, focus) = DorisPipesUi.createPreviewContent(fixture.editor, result)
            val sql = focus as LanguageTextField
            sql.setDisposedWith(testRootDisposable)
            assertTrue(sql.isViewer)
            assertEquals(result.dorisSql, sql.text)
            val previewEditor = checkNotNull(sql.getEditor(true))
            assertTrue(previewEditor.isViewer)
            assertEquals(result.dorisSql, previewEditor.document.text)
            val layout = content.layout as BorderLayout
            val sqlScroll = layout.getLayoutComponent(BorderLayout.CENTER) as JScrollPane
            assertSame(sql, sqlScroll.viewport.view)
            val warnings = (layout.getLayoutComponent(BorderLayout.NORTH) as JScrollPane).viewport.view as JTextArea
            assertFalse(warnings.isEditable)
            assertTrue(warnings.lineWrap)
            assertEquals(result.executionError!!.message, warnings.text)
            for (warning in result.unsupportedMessages) {
                assertTrue(warnings.text, warning in warnings.text)
                assertFalse(sql.text, warning in sql.text)
            }
            assertEquals(original, fixture.editor.document.text)
            assertEquals(stamp, fixture.editor.document.modificationStamp)
            assertEquals(2, fixture.editor.selectionModel.selectionStart)
            assertEquals(8, fixture.editor.selectionModel.selectionEnd)
            assertEmpty(fixture.requests)
            assertEmpty(fixture.previousEvents)
            assertEmpty(fixture.notifications)
        }
    }

    fun testExecutionNotificationPreservesMarkupLikeAndMultilineDiagnostics() {
        ExecutionFixture("FROM offline_rows |> SELECT id").use { fixture ->
            val message = "first <b>literal</b> & text\r\nsecond\rthird <script>still text</script>"
            DorisPipesExecution.notifyTranspileError(fixture.console, DorisPipesEngine.Transpile.Err(null, null, message))
            val notification = fixture.notifications.single()
            assertEquals("first &lt;b&gt;literal&lt;/b&gt; &amp; text<br>second<br>third &lt;script&gt;still text&lt;/script&gt;", notification.content)
            assertEquals(StringUtil.convertLineSeparators(message), notificationText(notification))
            assertEmpty(fixture.requests)
            assertEmpty(fixture.previousEvents)
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

    private inner class ExecutionFixture(sql: String, driverId: String = "doris") : AutoCloseable {
        private val virtualFile = myFixture.configureByText("offline-execution-${UUID.randomUUID()}.sql", sql).virtualFile
        // Setting the dialect invalidates the original generic-SQL PSI file.
        val file get() = checkNotNull(PsiManager.getInstance(project).findFile(virtualFile))
        val editor = myFixture.editor
        val requests = mutableListOf<GridDataRequest>()
        val previousEvents = mutableListOf<AnActionEvent>()
        val clients = mutableListOf<DatabaseSessionClient>()
        val auditors = mutableListOf<DataAuditor>()
        val consumers = mutableListOf<DataConsumer>()
        val notifications = mutableListOf<Notification>()
        private val recordedWork = java.util.concurrent.CopyOnWriteArrayList<DatabaseSession.State.Work>()
        private val unsupportedCalls = mutableListOf<String>()
        private val userData = UserDataHolderBase()
        private val notificationConnection = project.messageBus.connect()

        // Tests can inject a synchronous submission failure/cancellation or inspect a request.
        // Record before invoking the callback, so a failing submission remains observable.
        var onProcessRequest: (GridDataRequest) -> Unit = {}
        var beforeSessionCall: (String) -> Unit = {}
        var onAddAuditor: (DataAuditor) -> Unit = {}

        private val producer = object : DataProducer {
            override fun processRequest(request: GridDataRequest) {
                val dataRequest = request as DataRequest
                // Auditors and 263's gutter renderer expect submitted requests to have session
                // work already registered. Recording finishes immediately; no JDBC results exist.
                recordedWork.add(object : DatabaseSession.State.Work {
                    override val request = dataRequest
                    override val timeSpentMs = 0L
                    override val state = DatabaseSession.State.WorkState.FINISHED
                    override val status = object : DatabaseSession.State.WorkStatus {
                        override val type = DatabaseSession.State.WorkStatusType.UNKNOWN
                        override val description = "Recorded offline request"
                        override val errorNavigator: DataRequest.CoupledWithEditor.ErrorNavigator? = null
                    }
                })
                auditors.forEach { it.jobSubmitted(dataRequest, this) }
                requests.add(request)
                onProcessRequest(request)
            }
        }
        val bus = object : DataBus.Consuming {
            // Request submissions are audited; result delivery is outside this recording fixture.
            override fun filterFor(owner: DataRequest.Owner): DataBus.Consuming = this
            override fun getDataProducer(): DataProducer = producer
            override fun addConsumer(consumer: DataConsumer) { consumers.add(consumer) }
            override fun addAuditor(auditor: DataAuditor) {
                auditors.add(auditor)
                onAddAuditor(auditor)
            }
        }

        val point = LocalDataSource().apply {
            name = "Offline PIPE execution test ${UUID.randomUUID()}"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver(driverId))
            // Deliberately not a MySQL endpoint; even an accidental real connection cannot use it.
            url = "jdbc:offline:doris-pipes-test"
            isAutoSynchronize = false
            isKeepAlive = false
        }
        private val state = Proxy.newProxyInstance(
            DatabaseSession.State::class.java.classLoader,
            arrayOf(DatabaseSession.State::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Idle offline session state"
                "isIdle", "isFinalized" -> true
                "isEmpty" -> recordedWork.isEmpty()
                "isCancelled" -> false
                "getStartTime", "getTimeSpentMs" -> 0L
                "getWork" -> recordedWork.toList()
                "getWorkFor" -> recordedWork.lastOrNull { it.request === args!![0] || it.request.owner === args[0] }
                "getMostRecentWork" -> recordedWork.lastOrNull { work ->
                    (args!![0] as List<*>).any { it === work.request }
                }
                else -> throw UnsupportedOperationException("Offline state does not implement $method")
            }
        } as DatabaseSession.State
        val session = Proxy.newProxyInstance(
            DatabaseSession::class.java.classLoader, arrayOf(DatabaseSession::class.java),
        ) { proxy, method, args ->
            beforeSessionCall(method.name)
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString", "getTitle", "getDisplayName" -> "Offline PIPE recording session"
                "getConnectionPoint", "getTarget" -> point
                "getState" -> state
                "getProject" -> project
                "getMessageBus" -> bus
                "getClients" -> clients.toTypedArray()
                "getClientsWithFile" -> clients.filterIsInstance<DatabaseSessionClientWithFile>().toTypedArray()
                "attach" -> { clients.add(args!![0] as DatabaseSessionClient); null }
                "detach" -> { clients.remove(args!![0] as DatabaseSessionClient); null }
                "isValid", "isIdle" -> true
                "isConnected", "isInternal", "isService", "isCancelled" -> false
                "getCurrentTx" -> DataRequest.AUTO_COMMIT
                // DatabaseSession also extends UserDataHolder on 262.
                "getUserData" -> {
                    @Suppress("UNCHECKED_CAST")
                    userData.getUserData(args!![0] as Key<Any>)
                }
                "putUserData" -> {
                    @Suppress("UNCHECKED_CAST")
                    userData.putUserData(args!![0] as Key<Any>, args[1])
                    null
                }
                else -> {
                    unsupportedCalls.add(method.toString())
                    throw UnsupportedOperationException("Offline session does not implement $method")
                }
            }
        } as DatabaseSession
        @Suppress("UNNECESSARY_LATEINIT") // Cleanup also runs if construction fails before assignment.
        lateinit var console: JdbcConsole
            private set
        private val previous = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) { previousEvents.add(e) }
        }

        init {
            setSqlDialectMapping(project, virtualFile, if (driverId == "doris") DorisSqlDialect.INSTANCE else null)
            val manager = LocalDataSourceManager.getInstance(project)
            manager.addDataSource(point)
            try {
                // The manager invalidates DbPsiFacade via invokeLater. This synchronous fixture
                // must invalidate its cached source list before building a console: isValid and
                // getActiveConsoles consult that list, and the latter disposes missing targets.
                val facade = DbPsiFacade.getInstance(project)
                facade.clearCaches()
                assertNotNull("New source must be visible to console validity checks", facade.findDataSource(point.uniqueId))
                console = JdbcConsole.newConsole(project).forFile(virtualFile)
                    .fromDataSource(point).useSession(session).build()
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

        fun action(variant: Int = 1): DorisPipesRunQueryAction =
            if (variant == 4) DorisPipesRunSelectionAction(previous)
            else DorisPipesRunQueryAction(variant, previous)

        fun execute(variant: Int = 1): AnActionEvent = event().also { event ->
            assertSame(console, JdbcConsole.findConsole(event))
            action(variant).actionPerformed(event)
        }

        override fun close() {
            beforeSessionCall = {}
            onProcessRequest = {}
            onAddAuditor = {}
            notificationConnection.disconnect()
            notifications.forEach { it.expire() }
            try {
                if (this::console.isInitialized) {
                    // The fake session does not own/dispose its UI as a real session does.
                    // Cancellation can create console editors without ever querying session state.
                    Disposer.dispose(console.consoleView)
                    Disposer.dispose(console)
                }
            } finally {
                LocalDataSourceManager.getInstance(project).removeDataSource(point)
                // End the fixture's registry lifetime synchronously as well, before another lookup.
                DbPsiFacade.getInstance(project).clearCaches()
                setSqlDialectMapping(project, virtualFile, null)
            }
            assertEmpty("Unsupported fake methods must not be swallowed by production catch blocks", unsupportedCalls)
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
