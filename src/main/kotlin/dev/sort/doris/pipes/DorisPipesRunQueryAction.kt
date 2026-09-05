package dev.sort.doris.pipes

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.settings.DatabaseSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.ActionWrapperUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PerformWithDocumentsCommitted
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.TextEditor
import dev.sort.doris.DorisDbms

/**
 * A composable Execute replacement. The immediate previous action is captured at registration,
 * never looked up by ID at execution time. Remaining a [RunQueryAction] preserves the platform's
 * Execute classification; the index preserves live variant settings.
 */
internal open class DorisPipesRunQueryAction(
    index: Int,
    private val previous: AnAction,
    private val intercept: (AnActionEvent, DatabaseSettings.ExecOption) -> Boolean = PipesExecuteInterceptor::handle,
) : RunQueryAction(index), ActionWithDelegate<AnAction>, PerformWithDocumentsCommitted {
    init {
        copyFrom(previous)
    }

    override fun getDelegate(): AnAction = previous

    override fun update(e: AnActionEvent) = previous.update(e)

    override fun getActionUpdateThread(): ActionUpdateThread = previous.actionUpdateThread

    override fun isDumbAware(): Boolean = previous.isDumbAware

    override fun isInInjectedContext(): Boolean = previous.isInInjectedContext

    override fun isPerformWithDocumentsCommitted(): Boolean =
        PerformWithDocumentsCommitted.isPerformWithDocumentsCommitted(previous)

    override fun actionPerformed(e: AnActionEvent) {
        if (!intercept(e, getExecOption())) ActionWrapperUtil.actionPerformed(e, this, previous)
    }
}

internal class DorisPipesRunSelectionAction(
    previous: AnAction,
    intercept: (AnActionEvent, DatabaseSettings.ExecOption) -> Boolean = PipesExecuteInterceptor::handle,
) : DorisPipesRunQueryAction(-1, previous, intercept) {
    // Stock Selection uses fixed defaults, independent of the user's three Execute variants.
    private val selectionOption = DatabaseSettings.ExecOption().apply { execSelection = 1 }

    override fun getExecOption(): DatabaseSettings.ExecOption = selectionOption
}

/** Structure-view actions can supply FILE_EDITOR without supplying EDITOR. */
internal fun editorForPipeExecution(e: AnActionEvent): Editor? = e.getData(CommonDataKeys.EDITOR)
    ?: (e.getData(PlatformCoreDataKeys.FILE_EDITOR) as? TextEditor)?.editor

private object PipesExecuteInterceptor {

    fun handle(e: AnActionEvent, option: DatabaseSettings.ExecOption): Boolean {
        val console = JdbcConsole.findConsole(e) ?: return false
        if (!DorisPipes.isEnabled(console.project)) return false
        if (console.session.connectionPoint.dbms !== DorisDbms.DORIS) return false
        val editor = editorForPipeExecution(e) ?: return false
        val text = editor.selectionModel.selectedText
            ?: DorisPipes.chunkAt(editor.document.text, editor.caretModel.offset)?.text ?: return false
        if (!text.contains(DorisPipes.MARKER)) return false

        // Reuse stock document/Info preparation, including structure-view handling. The result is
        // invocation-local: preparation may return without reaching invokeImpl, or reenter Execute.
        var handled = false
        object : RunQueryAction(1) {
            // The stock method is OverrideOnly: invoke it from the corresponding override.
            override fun actionPerformed(e: AnActionEvent) = super.actionPerformed(e)

            override fun getExecOption(): DatabaseSettings.ExecOption = option

            override fun invokeImpl(e: AnActionEvent, console: JdbcConsole?, info: JdbcConsoleProvider.Info) {
                handled = handle(console, info)
            }
        }.actionPerformed(e)
        return handled
    }

    /**
     * True = a pipe program was handled (executed or error-ballooned) — the caller must NOT run
     * the previous action. False = delegate. The existing failure fallback is tracked as B10.
     */
    fun handle(console: JdbcConsole?, info: JdbcConsoleProvider.Info): Boolean = try {
        doHandle(console, info)
    } catch (t: Throwable) {
        DorisPipes.warn("pipe execute path failed; delegating execution: ${t.message}", t)
        false
    }

    private fun doHandle(console: JdbcConsole?, info: JdbcConsoleProvider.Info): Boolean {
        if (console == null || !DorisPipes.isEnabled(console.project)) return false
        val session = console.session
        if (session.connectionPoint.dbms !== DorisDbms.DORIS) return false
        val editor = info.editor ?: return false

        // Selection wins unchanged; only automatic ranges carry a lexical-boundary guard.
        val chunk = if (editor.selectionModel.hasSelection()) {
            null
        } else {
            DorisPipes.chunkAt(editor.document.text, editor.caretModel.offset) ?: return false
        }
        val text = chunk?.text ?: editor.selectionModel.selectedText ?: return false
        val selStart = chunk?.startOffset ?: editor.selectionModel.selectionStart
        if (!text.contains(DorisPipes.MARKER)) return false
        DorisPipes.info("execute intercept: candidate pipe chunk (${text.length} chars)")

        val result = if (chunk != null) DorisPipesEngine.transpile(chunk) else DorisPipesEngine.transpile(text)
        return when (result) {
            is DorisPipesEngine.Transpile.NotPipe -> false
            is DorisPipesEngine.Transpile.Err -> {
                DorisPipesExecution.notifyTranspileError(console, result)
                true // handled: running the raw pipe text would only produce a worse server error
            }
            is DorisPipesEngine.Transpile.Ok -> {
                // Engine offsets are relative to the TRIMMED text (transpile trims before parsing).
                val trimAnchor = selStart + (text.length - text.trimStart().length)
                val vf = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
                val range = com.intellij.openapi.util.TextRange(trimAnchor, selStart + text.trimEnd().length)
                DorisPipesExecution.submit(
                    console, result.dorisSql, text, result.result,
                    PipeAnchor(editor, range, vf, trimAnchor, editor.document.text.hashCode()),
                )
            }
        }
    }
}

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
                "Parse error$where",
                "The parser reported: \"${err.message}\"",
                NotificationType.ERROR,
            )
            .notify(console.project)
    }

    /** Submit [dorisSql] through the console session's own request bus. False = no attached client. */
    fun submit(
        console: JdbcConsole,
        dorisSql: String,
        originalText: String,
        transpile: dev.brikk.house.sql.shape.TranspileResult? = null,
        anchor: PipeAnchor? = null,
    ): Boolean {
        val session = console.session
        val client = session.clientsWithFile.firstOrNull()
            ?: return false // no attached console client — let stock produce its own error
        DorisPipes.info(
            "session '${session.title}': pipe program (${originalText.length} chars) -> executing " +
                "canonical Doris SQL (${dorisSql.length} chars)",
        )
        // Anchored request (spinner/gutter coupling) when we know the editor span; plain request
        // as the fallback so an anchoring failure can never break execution itself.
        val request: DataRequest = anchor?.let { a ->
            runCatching {
                PipeQueryRequest(client, dorisSql, session.connectionPoint.dbms, a.editor, a.range) as DataRequest
            }.getOrNull()
        } ?: DataRequest.newRequest(client, dorisSql, session.connectionPoint.dbms)
        // Server errors travel the AUDIT stream, not the request promise (task #19 finding): a
        // per-bus DataAuditor watches error(ctx, info) for OUR requests (identity match) and maps
        // the reported transpiled position back to the user's pipe text.
        // A new run supersedes the previous run's editor mark for this file.
        anchor?.file?.let { DorisPipes.clearExecMark(console.project, it.url) }
        registerRun(
            console, request, dorisSql, originalText, transpile,
            anchor?.file, anchor?.trimAnchor ?: 0, anchor?.docHash ?: 0,
        )
        session.messageBus.dataProducer.processRequest(request)
        return true
    }

    /** In-flight pipe runs by request identity (weak — entries die with the request). */
    private data class PipeRun(
        val console: JdbcConsole,
        val dorisSql: String,
        val originalText: String,
        val transpile: dev.brikk.house.sql.shape.TranspileResult?,
        val markFile: com.intellij.openapi.vfs.VirtualFile?,
        val markAnchor: Int,
        val markDocHash: Int,
    )
    private val runs = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, PipeRun>())
    private val audited = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())

    private fun registerRun(
        console: JdbcConsole,
        request: DataRequest,
        dorisSql: String,
        originalText: String,
        transpile: dev.brikk.house.sql.shape.TranspileResult?,
        markFile: com.intellij.openapi.vfs.VirtualFile?,
        markAnchor: Int,
        markDocHash: Int,
    ) {
        runs[request] = PipeRun(console, dorisSql, originalText, transpile, markFile, markAnchor, markDocHash)
        val bus = console.session.messageBus
        // One auditor per session bus for the plugin's lifetime (weak-keyed dedupe) — it consults
        // the in-flight map, so it is inert for non-pipe requests.
        if (audited.put(bus, true) == null) {
            bus.addAuditor(object : com.intellij.database.datagrid.DataAuditor {
                override fun error(
                    context: DataRequest.Context,
                    info: com.intellij.database.connection.throwable.info.ErrorInfo,
                ) {
                    val run = runs[context.request] ?: return
                    runCatching { balloonMappedError(run, info) }
                }
            })
        }
    }

    private fun balloonMappedError(run: PipeRun, info: com.intellij.database.connection.throwable.info.ErrorInfo) {
        val message = runCatching { info.message }.getOrNull() ?: return
        if (!message.contains("(line ")) return
        val mapped = run.transpile?.let { DorisPipesEngine.mapServerErrorExact(message, it) }
            ?: DorisPipes.mapServerError(message, run.dorisSql, run.originalText) ?: return
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
                runCatching {
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(run.console.project).restart()
                }
            }
        }
    }
}
