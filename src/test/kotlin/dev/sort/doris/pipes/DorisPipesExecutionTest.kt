package dev.sort.doris.pipes

import com.intellij.database.DataBus
import com.intellij.database.Dbms
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
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiManager
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.LanguageTextField
import dev.sort.doris.DorisDbms
import dev.sort.doris.sql.DorisSqlDialect
import org.antlr.v4.runtime.Token
import org.apache.doris.nereids.DorisLexer
import org.apache.doris.sqlparser.DorisSqlParser
import java.lang.reflect.Proxy
import java.awt.BorderLayout
import java.util.concurrent.CancellationException
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
        for (sql in warningPrograms) {
            val translation = DorisPipesEngine.transpile(sql) as DorisPipesEngine.Transpile.Ok
            assertNotNull(translation.executionError)
            ExecutionFixture(sql).use { fixture ->
                for (variant in 1..4) {
                    fixture.replaceSql(sql)
                    fixture.execute(variant)
                    assertEquals("warning query must not submit: variant=$variant sql=$sql", variant - 1, fixture.requests.size)
                    assertEmpty(fixture.previousEvents)
                    assertEquals(variant, fixture.notifications.size)
                    val notification = fixture.notifications.last()
                    assertEquals(NotificationType.ERROR, notification.type)
                    val text = notificationText(notification)
                    assertEquals(translation.executionError!!.message, text)
                    for (warning in translation.unsupportedMessages) assertTrue(text, warning in text)
                    assertEquals(sql, fixture.editor.document.text)

                    fixture.replaceSql(safeProgram)
                    fixture.execute(variant)
                    assertEquals(variant, fixture.requests.size)
                    assertEquals((DorisPipesEngine.transpile(safeProgram) as DorisPipesEngine.Transpile.Ok).dorisSql,
                        (fixture.requests.last() as DataRequest.QueryRequest).query)
                    assertEquals("the next query must not inherit warnings", variant, fixture.notifications.size)
                    assertEmpty(fixture.previousEvents)
                    assertEquals(safeProgram, fixture.editor.document.text)
                }
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

    fun testMixedAndMultiplePipeSelectionsNeverSubmitOrDelegate() {
        for (sql in listOf(
            "$safeProgram; SELECT 1", "SELECT 1; $safeProgram", "$safeProgram; $safeProgram",
            "$safeProgram; SELECT '", "$safeProgram; /* unclosed", "$safeProgram; FROM broken |> WHERE",
            "$safeProgram |> WHERE", "$safeProgram |> WHERE id = 'unfinished; SELECT 1",
        )) {
            ExecutionFixture(sql).use { fixture ->
                fixture.editor.selectionModel.setSelection(0, sql.length)
                for (variant in 1..4) {
                    fixture.execute(variant)
                    assertEmpty("variant=$variant sql=$sql", fixture.requests)
                    assertEmpty(fixture.previousEvents)
                    assertEquals(variant, fixture.notifications.size)
                    assertEquals(NotificationType.ERROR, fixture.notifications.last().type)
                    assertEquals(sql, fixture.editor.document.text)
                }
            }
        }
    }

    fun testMissingClientReportsOnceWithoutFallbackForEveryVariant() {
        ExecutionFixture(safeProgram).use { fixture ->
            fixture.editor.selectionModel.setSelection(0, safeProgram.length)
            fixture.clients.clear()
            for (variant in 1..4) {
                fixture.execute(variant)
                assertEmpty(fixture.requests)
                assertEmpty(fixture.previousEvents)
                assertEquals(variant, fixture.notifications.size)
                val notification = fixture.notifications.last()
                assertEquals(NotificationType.ERROR, notification.type)
                assertTrue(notification.content, "no attached console client" in notification.content)
                assertTrue(notification.content, "original SQL was not executed" in notification.content)
                assertEquals(safeProgram, fixture.editor.document.text)
            }
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

    fun testRequestSetupAuditorAndProducerFailuresNeverRetryOrDelegate() {
        for (site in listOf("request setup", "auditor registration", "producer")) {
            for (failure in listOf(IllegalStateException("injected $site"), ProcessCanceledException(),
                CancellationException("cancelled $site"))) {
                for (variant in 1..4) {
                    ExecutionFixture(safeProgram).use { fixture ->
                        fixture.editor.selectionModel.setSelection(0, safeProgram.length)
                        var failedCalls = 0
                        when (site) {
                            "request setup" -> fixture.beforeSessionCall = { method ->
                                // Fail the anchored request constructor's lookup, not the ownership pre-gate.
                                if (method == "getConnectionPoint" && Thread.currentThread().stackTrace.any {
                                    it.className == DorisPipesExecution::class.java.name && it.methodName == "submit"
                                }) {
                                    failedCalls++
                                    throw failure
                                }
                            }
                            "auditor registration" -> fixture.onAddAuditor = { failedCalls++; throw failure }
                            "producer" -> fixture.onProcessRequest = { failedCalls++; throw failure }
                        }
                        val actual = runCatching { fixture.execute(variant) }.exceptionOrNull()
                        assertSame("site=$site variant=$variant", failure, actual)
                        assertEquals("the failing operation must be reached exactly once", 1, failedCalls)
                        assertEquals(if (site == "producer") 1 else 0, fixture.requests.size)
                        if (site == "producer") {
                            val request = fixture.requests.single() as DataRequest.QueryRequest
                            assertSame(fixture.console, request.owner)
                            assertFalse(request.query, "|>" in request.query)
                            assertTrue(request is DataRequest.CoupledWithEditor)
                        }
                        assertEmpty(fixture.previousEvents)
                        assertEmpty(fixture.notifications)
                        assertEquals(safeProgram, fixture.editor.document.text)
                    }
                }
            }
        }
    }

    fun testDirectSubmissionRejectsWarningsAndInvalidPayloadsBeforeSessionAccess() {
        val safe = DorisPipesEngine.transpile(safeProgram) as DorisPipesEngine.Transpile.Ok
        val lossy = DorisPipesEngine.transpile(warningPrograms.first()) as DorisPipesEngine.Transpile.Ok
        ExecutionFixture(safeProgram).use { fixture ->
            for (translation in listOf(lossy, DorisPipesEngine.Transpile.Ok("SELECT 1"), safe.copy(dorisSql = "SELECT 2"))) {
                fixture.sessionCalls.clear()
                val failure = runCatching { DorisPipesExecution.submit(fixture.console, translation, safeProgram) }.exceptionOrNull()
                assertTrue("expected the submission guard, got $failure", failure is IllegalStateException)
                assertEmpty(fixture.sessionCalls)
                assertEmpty(fixture.requests)
                assertEmpty(fixture.previousEvents)
            }
        }
    }

    fun testRunToStageIntentionAndMenuBlockLossyPrefixesInTheRealConsole() {
        for (sql in warningPrograms) {
            ExecutionFixture(sql).use { fixture ->
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
        private val virtualFile = myFixture.configureByText("offline-execution.sql", sql).virtualFile
        // Setting the dialect invalidates the original generic-SQL PSI file.
        val file get() = checkNotNull(PsiManager.getInstance(project).findFile(virtualFile))
        val editor = myFixture.editor
        val requests = mutableListOf<GridDataRequest>()
        val previousEvents = mutableListOf<AnActionEvent>()
        val clients = mutableListOf<DatabaseSessionClient>()
        val auditors = mutableListOf<DataAuditor>()
        val consumers = mutableListOf<DataConsumer>()
        val sessionCalls = mutableListOf<String>()
        val notifications = mutableListOf<Notification>()
        private val unsupportedCalls = mutableListOf<String>()
        private val userData = UserDataHolderBase()
        private val notificationConnection = project.messageBus.connect()

        // Tests can inject a synchronous submission failure/cancellation or inspect a request.
        // Record before invoking the callback, so a failing submission remains observable.
        var onProcessRequest: (GridDataRequest) -> Unit = {}
        var beforeSessionCall: (String) -> Unit = {}
        var onAddAuditor: (DataAuditor) -> Unit = {}

        private val producer = DataProducer { request ->
            requests.add(request)
            onProcessRequest(request)
        }
        val bus = object : DataBus.Consuming {
            // No events are published, so owner filtering has no delivery behavior to simulate.
            override fun filterFor(owner: DataRequest.Owner): DataBus.Consuming = this
            override fun getDataProducer(): DataProducer = producer
            override fun addConsumer(consumer: DataConsumer) { consumers.add(consumer) }
            override fun addAuditor(auditor: DataAuditor) {
                auditors.add(auditor)
                onAddAuditor(auditor)
            }
        }

        val point = LocalDataSource().apply {
            name = "Offline PIPE execution test"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver(driverId))
            // Deliberately not a MySQL endpoint; even an accidental real connection cannot use it.
            url = "jdbc:offline:doris-pipes-test"
            isAutoSynchronize = false
            isKeepAlive = false
        }
        val session = Proxy.newProxyInstance(
            DatabaseSession::class.java.classLoader, arrayOf(DatabaseSession::class.java),
        ) { proxy, method, args ->
            sessionCalls.add(method.name)
            beforeSessionCall(method.name)
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString", "getTitle", "getDisplayName" -> "Offline PIPE recording session"
                "getConnectionPoint", "getTarget" -> point
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
            SqlDialectMappings.getInstance(project).setMapping(virtualFile, DorisSqlDialect.INSTANCE)
            val manager = LocalDataSourceManager.getInstance(project)
            manager.addDataSource(point)
            try {
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
                if (this::console.isInitialized) Disposer.dispose(console)
            } finally {
                LocalDataSourceManager.getInstance(project).removeDataSource(point)
                SqlDialectMappings.getInstance(project).setMapping(virtualFile, null)
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
}
