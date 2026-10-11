package dev.sort.doris.pipes

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.settings.DatabaseSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Disposer
import dev.brikk.house.sql.intellij.EditorSnapshot
import dev.sort.doris.DorisDbms

/** Structure-view actions can supply FILE_EDITOR without supplying EDITOR. */
internal fun editorForPipeExecution(e: AnActionEvent): Editor? = e.getData(CommonDataKeys.EDITOR)
    ?: (e.getData(PlatformCoreDataKeys.FILE_EDITOR) as? TextEditor)?.editor

/** Keep the handled/delegate decision testable without creating a live JDBC console. */
internal fun dispatchPipeTranslation(
    result: DorisPipesEngine.Transpile,
    reportError: (DorisPipesEngine.Transpile.Err) -> Unit,
    submit: (DorisPipesEngine.Transpile.Ok) -> Boolean,
): Boolean = when (result) {
    is DorisPipesEngine.Transpile.NotPipe -> false
    is DorisPipesEngine.Transpile.Err -> {
        reportError(result)
        true
    }
    is DorisPipesEngine.Transpile.Ok -> {
        val error = result.executionError
        if (error != null) reportError(error)
        else if (!submit(result)) reportError(DorisPipesEngine.Transpile.Err(
            null, null, "PIPE query could not be submitted: no attached console client. The original SQL was not executed.",
        ))
        true // A claimed PIPE never delegates, even when submission cannot start.
    }
}

internal object PipesExecuteInterceptor {

    fun handle(e: AnActionEvent, option: DatabaseSettings.ExecOption): Boolean {
        if (dev.sort.doris.brikk.BrikkDorisContext.isActive(e.getData(CommonDataKeys.PSI_FILE))) {
            e.project?.let { project ->
                NotificationGroupManager.getInstance().getNotificationGroup("Doris Pipes")
                    .createNotification("Brikk SQL is inspection-only",
                        "Execute the compiled/composed relation with its binding map, not the IDE template placeholders.",
                        NotificationType.WARNING).notify(project)
            }
            return true
        }
        val console = JdbcConsole.findConsole(e) ?: return false
        if (!DorisPipes.isEnabled(console.project)) return false
        if (console.session.connectionPoint.dbms !== DorisDbms.DORIS) return false
        val editor = editorForPipeExecution(e) ?: return false
        val candidate = pipeExecutionCandidate(editor, option) ?: return false
        if (!DorisPipes.containsPipeOperator(candidate)) return false
        val boundary = DorisPipes.chunks(candidate).firstOrNull { it.boundaryError != null }
        if (boundary?.boundaryError != null) {
            notifyBoundaryError(console, boundary.boundaryError)
            return true
        }

        // Claim before stock preparation. Cancellation or a chooser dismissal cannot grant raw fallback.
        val owner = console
        val document = editor.document
        var edited = false
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { edited = true }
        }
        val preparation = Disposer.newDisposable("Doris PIPE preparation")
        document.addDocumentListener(listener, preparation)
        var snapshot: EditorSnapshot? = null
        try {
            object : RunQueryAction(1) {
                // The stock method is OverrideOnly: invoke it from the corresponding override.
                override fun actionPerformed(e: AnActionEvent) = super.actionPerformed(e)

                override fun getExecOption(): DatabaseSettings.ExecOption {
                    // Stock preparation commits and saves first. Saving unchanged content can
                    // reset its modification stamp without a document edit. Track real edits
                    // through that phase, then pin the helper's revision after the save.
                    check(!edited) { "SQL changed during preparation. Run the current statement again." }
                    snapshot = EditorSnapshot.capture(document)
                    return option
                }

                override fun invokeImpl(e: AnActionEvent, console: JdbcConsole?, info: JdbcConsoleProvider.Info) {
                    if (console == null) return
                    check(console === owner) { "The destination console changed during PIPE preparation" }
                    val prepared = checkNotNull(snapshot)
                    prepared.requireCurrent()
                    handle(console, info, prepared)
                }
            }.actionPerformed(e)
        } finally { Disposer.dispose(preparation) }
        return true
    }

    /**
     * True = a pipe program was handled (executed or error-ballooned) — the caller must NOT run
     * the previous action. False is reserved for unclaimed input. Exceptions propagate to the
     * IDE; neither cancellation nor a failed claimed request grants permission for raw fallback.
     */
    fun handle(console: JdbcConsole?, info: JdbcConsoleProvider.Info, snapshot: EditorSnapshot): Boolean {
        if (console == null || !DorisPipes.isEnabled(console.project)) return false
        if (console.session.connectionPoint.dbms !== DorisDbms.DORIS) return false
        val destination = PipeDestination(console)
        val editor = info.editor ?: return false
        if (info.model.statements().none { DorisPipes.containsPipeOperator(it.query()) }) return false

        JdbcConsoleProvider.chooseStatements(info, "Nothing to run", true) { selected ->
            snapshot.requireCurrent()
            if (selected.statements().none { DorisPipes.containsPipeOperator(it.query()) }) {
                if (console.beforeExecuteQueries(selected)) console.executeQueries(editor, selected, info.execOption)
                return@chooseStatements
            }
            val model = try {
                PipeScriptModel(selected, editor)
            } catch (failure: PipeTranslationFailure) {
                DorisPipesExecution.notifyTranspileError(console, failure.error)
                return@chooseStatements
            }
            if (console.beforeExecuteQueries(model)) {
                model.finalizeTranslations(console.pStorage)?.let { error ->
                    DorisPipesExecution.notifyTranspileError(console, error)
                    return@chooseStatements
                }
                DorisPipesExecution.track(console, model.executionPlans)
                try {
                    snapshot.requireCurrent()
                    destination.requireCurrent(console)
                    console.executeQueries(editor, model, info.execOption)
                } finally {
                    DorisPipesExecution.endTracking(console)
                }
            }
        }
        return true
    }

    private fun notifyBoundaryError(console: JdbcConsole, message: String) {
        DorisPipesExecution.notifyTranspileError(console, DorisPipesEngine.Transpile.Err(null, null, message))
    }
}

internal class PipeTranslationFailure(val error: DorisPipesEngine.Transpile.Err) : RuntimeException(error.message)

/**
 * A [DataRequest.QueryRequest] carrying the TRANSPILED Doris SQL while implementing
 * [DataRequest.CoupledWithEditor] over the ORIGINAL pipe span — the same coupling the stock
 * ConsoleDataRequest provides, which is what the platform's execution tracking decorates
 * (running indicator over the statement, gutter cancel, error focus). Mirrors the stock
 * `DataRequest.newRequest(owner, query, dbms)` construction (QueryRequest + newConstraints(dbms)).
 */
private class PipeQueryRequest(
    owner: DataRequest.OwnerEx,
    dorisSql: String,
    dbms: com.intellij.database.Dbms,
    private val editor: com.intellij.openapi.editor.Editor,
    private val range: com.intellij.openapi.util.TextRange,
) : DataRequest.QueryRequest(owner, dorisSql, newConstraints(dbms), null),
    DataRequest.CoupledWithEditor {
    override fun getEditor(): com.intellij.openapi.editor.Editor = editor
    override fun getRange(): com.intellij.openapi.util.TextRange = range
    override fun getRequest(): com.intellij.database.datagrid.GridDataRequest = this
    override fun onError(
        info: com.intellij.database.connection.throwable.info.ErrorInfo,
    ): DataRequest.CoupledWithEditor.ErrorNavigator? = null
    override fun onWarning(w: com.intellij.database.console.JdbcEngineUtils.EngineWarningExceptionInfo) {}
}

/** Everything needed to anchor a pipe run to its editor span and to place the error mark. */
internal data class PipeAnchor(
    val editor: com.intellij.openapi.editor.Editor,
    val range: com.intellij.openapi.util.TextRange,
    val file: com.intellij.openapi.vfs.VirtualFile?,
    val trimAnchor: Int,
    val docHash: Int,
)

/** The console target captured before parameter prompting, which can edit or replace it. */
internal class PipeDestination(console: JdbcConsole) {
    private val session = console.session
    private val dataSource = console.dataSource
    private val url = dataSource?.url

    fun requireCurrent(console: JdbcConsole) {
        check(DorisPipes.isEnabled(console.project)) { "PIPE was disabled during preparation" }
        check(
            console.isValid && console.session === session && console.dataSource === dataSource &&
                dataSource?.url == url && session.connectionPoint.dbms === DorisDbms.DORIS,
        ) { "The destination changed during PIPE preparation" }
    }
}

/**
 * Shared pipe-execution machinery for the execute-action interceptor and the pipe intentions
 * (preview / run-to-stage). Owns the platform submission path and the user-facing notifications.
 */
internal object DorisPipesExecution {

    fun notifyTranspileError(console: JdbcConsole, err: DorisPipesEngine.Transpile.Err) {
        val where = err.line?.let { " at line ${err.line}, col ${err.col}" } ?: ""
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Doris Pipes")
            .createNotification(
                "PIPE translation failed$where",
                pipeNotificationHtml(err.message),
                NotificationType.ERROR,
            )
            .notify(console.project)
    }

    /** Submit a fixed-scope helper request through the exact console that initiated it. */
    fun submit(
        console: JdbcConsole,
        destination: PipeDestination,
        translation: DorisPipesEngine.Transpile.Ok,
        originalText: String,
        anchor: PipeAnchor? = null,
        exactSourceMap: Boolean = true,
    ): Boolean {
        check(translation.executionError == null) { translation.executionError!!.message }
        val transpile = checkNotNull(translation.result) { "PIPE submission requires the engine's translation result" }
        check(transpile.sql == translation.dorisSql) { "PIPE SQL does not match its translation result" }
        val dorisSql = translation.dorisSql
        ProgressManager.checkCanceled()
        destination.requireCurrent(console)
        val session = console.session
        DorisPipes.info(
            "session '${session.title}': pipe program (${originalText.length} chars) -> executing " +
                "canonical Doris SQL (${dorisSql.length} chars)",
        )
        // An anchoring failure must stop this request, not retry it without editor coupling.
        val request: DataRequest = anchor?.let { a ->
            PipeQueryRequest(console, dorisSql, session.connectionPoint.dbms, a.editor, a.range)
        } ?: DataRequest.newRequest(console, dorisSql, session.connectionPoint.dbms)
        // Server errors travel the AUDIT stream, not the request promise (task #19 finding): a
        // per-bus DataAuditor watches error(ctx, info) for OUR requests (identity match) and maps
        // the reported transpiled position back to the user's pipe text.
        // A new run supersedes the previous run's editor mark for this file.
        anchor?.file?.let { DorisPipes.clearExecMark(console.project, it.url) }
        registerRun(
            console, request, dorisSql,
            if (exactSourceMap) { message -> DorisPipesEngine.mapServerErrorExact(message, transpile) } else null,
            anchor?.file, anchor?.trimAnchor ?: 0, anchor?.docHash ?: 0,
        )
        ProgressManager.checkCanceled()
        session.messageBus.dataProducer.processRequest(request)
        return true
    }

    /** In-flight pipe runs by request identity (weak — entries die with the request). */
    private data class PipeRun(
        val console: JdbcConsole,
        val dorisSql: String,
        val mapError: ((String) -> DorisPipes.MappedError?)?,
        val markFile: com.intellij.openapi.vfs.VirtualFile?,
        val markAnchor: Int,
        val markDocHash: Int,
    )
    private val runs = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, PipeRun>())
    private val audited = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
    private data class ExecutionSlot(val plan: PipePlan?)
    private class TrackedExecution(
        val console: JdbcConsole,
        plans: List<PipePlan?>,
    ) {
        val slots = plans.map(::ExecutionSlot)
    }
    private val trackedExecutionKey = Key.create<TrackedExecution>("DorisPipes.trackedExecution")
    private val startingExecution = ThreadLocal<TrackedExecution?>()

    fun track(console: JdbcConsole, plans: List<PipePlan?>) {
        check(startingExecution.get() == null) { "Nested PIPE execution tracking is not supported" }
        val execution = TrackedExecution(console, plans)
        startingExecution.set(execution)
        try {
            ensureAuditor(console)
        } catch (failure: Throwable) {
            startingExecution.remove()
            throw failure
        }
    }

    fun endTracking(console: JdbcConsole) {
        if (startingExecution.get()?.console === console) startingExecution.remove()
    }

    private fun registerRun(
        console: JdbcConsole,
        request: DataRequest,
        dorisSql: String,
        mapError: ((String) -> DorisPipes.MappedError?)?,
        markFile: com.intellij.openapi.vfs.VirtualFile?,
        markAnchor: Int,
        markDocHash: Int,
    ) {
        runs[request] = PipeRun(console, dorisSql, mapError, markFile, markAnchor, markDocHash)
        ensureAuditor(console)
    }

    private fun ensureAuditor(console: JdbcConsole) {
        val bus = console.session.messageBus
        // One auditor per session bus for the plugin's lifetime (weak-keyed dedupe) — it consults
        // the in-flight map, so it is inert for non-pipe requests.
        synchronized(audited) {
            if (audited.containsKey(bus)) return
            bus.addAuditor(object : com.intellij.database.datagrid.DataAuditor {
                override fun jobSubmitted(request: DataRequest, producer: com.intellij.database.datagrid.DataProducer) {
                    val consoleRequest = request as? ConsoleDataRequest ?: return
                    val shared = consoleRequest.sharedDataHolder
                    val execution = shared.getUserData(trackedExecutionKey)
                        ?: startingExecution.get()?.takeIf { it.console === request.owner }?.also {
                            shared.putUserData(trackedExecutionKey, it)
                        }
                        ?: return
                    val slot = execution.slots.getOrNull(consoleRequest.queryIndex) ?: return
                    val plan = slot.plan ?: return
                    val query = consoleRequest.query
                    if (plan.dorisSql != query) return
                    val text = plan.sourceQuery
                    val anchor = plan.anchor
                    anchor.file?.let { DorisPipes.clearExecMark(execution.console.project, it.url) }
                    runs[request] = PipeRun(
                        execution.console,
                        query,
                        { message -> mappedPipeError(message, text, plan.authoredMap) },
                        anchor.file,
                        anchor.trimAnchor,
                        anchor.docHash,
                    )
                }

                override fun error(
                    context: DataRequest.Context,
                    info: com.intellij.database.connection.throwable.info.ErrorInfo,
                ) {
                    val run = runs[context.request] ?: return
                    runPipeCatching { balloonMappedError(run, info) }
                }
            })
            audited[bus] = true
        }
    }

    private fun balloonMappedError(run: PipeRun, info: com.intellij.database.connection.throwable.info.ErrorInfo) {
        val message = runPipeCatching { info.message }.getOrNull() ?: return
        if (!message.contains("(line ")) return
        val mapped = run.mapError?.invoke(message) ?: return
        if (mapped.originalLine == null) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Doris Pipes")
            .createNotification(
                "SQL execution error at line ${mapped.originalLine}" +
                    (mapped.token?.let { tok -> " ('$tok')" } ?: ""),
                "For the translated SQL, the error was reported as:\n$message\n" +
                    "(right-click \u2192 Doris Pipes \u2192 Preview Generated SQL)",
                NotificationType.WARNING,
            )
            .notify(run.console.project)
        // Editor squiggle at the exact mapped span (engine 0-based char offsets, end inclusive).
        val file = run.markFile
        if (file != null && mapped.startOffset != null && mapped.endOffset != null) {
            DorisPipes.setExecMark(
                run.console.project,
                file.url,
                DorisPipes.ExecMark(
                    start = run.markAnchor + mapped.startOffset,
                    end = run.markAnchor + mapped.endOffset + 1,
                    message = message,
                    docHash = run.markDocHash,
                ),
            )
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                runPipeCatching {
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(run.console.project).restart()
                }
            }
        }
    }
}
