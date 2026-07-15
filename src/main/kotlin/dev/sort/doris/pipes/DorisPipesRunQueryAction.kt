package dev.sort.doris.pipes

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.script.ScriptModel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.sort.doris.DorisDbms

/**
 * Replaces the FOUR stock console execute actions (`Console.Jdbc.Execute[.2/.3/.Selection]`,
 * `overrides="true"` in plugin.xml) — the transpile-on-run half of the Doris Pipes SPIKE
 * (see [DorisPipes]).
 *
 * Interception happens at [RunQueryAction]'s EVENT-level `invokeImpl` overload — the first common
 * entry after `actionPerformed` for every execute variant — and again at the model-level overload
 * as a belt-and-braces (both delegate to the same idempotent [PipesExecuteInterceptor]).
 *
 * ## Why the pipe text comes from the DOCUMENT, not the platform's ScriptModel
 * The spike does no `|>` lexer masking, so the substrate PSI mangles a pipe program's statement
 * boundaries (observed live: no/wrong execution-block highlight). The platform's
 * statement-under-caret model therefore hands FRAGMENTS. The interceptor instead takes the
 * editor SELECTION if there is one, else the `;`-separated chunk of raw document text around the
 * caret ([DorisPipes.chunkAt]) — correct regardless of the broken PSI. (The block-highlight
 * cosmetics remain wrong in the spike; fixing that means teaching the parser pipe statement
 * boundaries — a P1 item, noted in IDEAS §3.)
 *
 * Behavior:
 *  - Doris console + pipes flag on + chunk is a valid pipe program (engine verdict): transpile to
 *    canonical Doris SQL and submit through the session's own request bus — results land in the
 *    normal grid, and the output log shows the generated Doris SQL (the §3 trust surface).
 *  - Engine rejects a pipe-looking chunk: error balloon with the engine's line/col; stock is NOT
 *    invoked (the server would only produce a worse error for pipe text).
 *  - Anything else (non-Doris, flag off, no pipe marker, any spike-path failure): stock execution.
 */
class DorisPipesRunQueryAction : RunQueryAction.Alt1() {
    override fun invokeImpl(e: AnActionEvent, console: JdbcConsole?, info: JdbcConsoleProvider.Info) {
        if (!PipesExecuteInterceptor.handle(console, info)) super.invokeImpl(e, console, info)
    }

    override fun invokeImpl(console: JdbcConsole?, model: ScriptModel<*>, info: JdbcConsoleProvider.Info) {
        if (!PipesExecuteInterceptor.handle(console, info)) super.invokeImpl(console, model, info)
    }
}

/** Same interception for the settings-variant `Console.Jdbc.Execute.2`. */
class DorisPipesRunQueryAction2 : RunQueryAction.Alt2() {
    override fun invokeImpl(e: AnActionEvent, console: JdbcConsole?, info: JdbcConsoleProvider.Info) {
        if (!PipesExecuteInterceptor.handle(console, info)) super.invokeImpl(e, console, info)
    }

    override fun invokeImpl(console: JdbcConsole?, model: ScriptModel<*>, info: JdbcConsoleProvider.Info) {
        if (!PipesExecuteInterceptor.handle(console, info)) super.invokeImpl(console, model, info)
    }
}

/** Same interception for the settings-variant `Console.Jdbc.Execute.3`. */
class DorisPipesRunQueryAction3 : RunQueryAction.Alt3() {
    override fun invokeImpl(e: AnActionEvent, console: JdbcConsole?, info: JdbcConsoleProvider.Info) {
        if (!PipesExecuteInterceptor.handle(console, info)) super.invokeImpl(e, console, info)
    }

    override fun invokeImpl(console: JdbcConsole?, model: ScriptModel<*>, info: JdbcConsoleProvider.Info) {
        if (!PipesExecuteInterceptor.handle(console, info)) super.invokeImpl(console, model, info)
    }
}

/** Same interception for `Console.Jdbc.Execute.Selection` (run selection as one statement). */
class DorisPipesRunSelectionAction : RunQueryAction.RunSelectionExactlyAsOneStatement() {
    override fun invokeImpl(e: AnActionEvent, console: JdbcConsole?, info: JdbcConsoleProvider.Info) {
        if (!PipesExecuteInterceptor.handle(console, info)) super.invokeImpl(e, console, info)
    }

    override fun invokeImpl(console: JdbcConsole?, model: ScriptModel<*>, info: JdbcConsoleProvider.Info) {
        if (!PipesExecuteInterceptor.handle(console, info)) super.invokeImpl(console, model, info)
    }
}

private object PipesExecuteInterceptor {

    /**
     * True = a pipe program was handled (executed or error-ballooned) — the caller must NOT run
     * stock. False = not ours; run stock. Never throws (any failure logs and returns false).
     */
    fun handle(console: JdbcConsole?, info: JdbcConsoleProvider.Info): Boolean = try {
        doHandle(console, info)
    } catch (t: Throwable) {
        DorisPipes.warn("pipe execute path failed; falling back to stock: ${t.message}", t)
        false
    }

    private fun doHandle(console: JdbcConsole?, info: JdbcConsoleProvider.Info): Boolean {
        if (console == null || !DorisPipes.enabled) return false
        val session = console.session
        if (session.connectionPoint.dbms !== DorisDbms.DORIS) return false
        val editor = info.editor ?: return false

        // Selection wins (run-selection semantics); else the raw-text chunk around the caret.
        val selStart: Int
        val text: String
        if (editor.selectionModel.hasSelection()) {
            text = editor.selectionModel.selectedText ?: return false
            selStart = editor.selectionModel.selectionStart
        } else {
            val chunk = DorisPipes.chunkAt(editor.document.text, editor.caretModel.offset) ?: return false
            text = chunk.text
            selStart = chunk.startOffset
        }
        if (!text.contains(DorisPipes.MARKER)) return false
        DorisPipes.info("execute intercept: candidate pipe chunk (${text.length} chars)")

        return when (val result = DorisPipesEngine.transpile(text)) {
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
        anchor?.file?.let { DorisPipes.clearExecMark(it.url) }
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
