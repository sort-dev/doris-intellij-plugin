package dev.sort.doris.pipes

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.database.script.ScriptModel
import com.intellij.database.script.ScriptModelUtilCore
import com.intellij.database.script.translator.TranslateException
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
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Conditions
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.util.containers.JBIterable
import dev.sort.doris.DorisDbms
import org.antlr.v4.runtime.Token
import org.apache.doris.nereids.DorisLexer
import org.apache.doris.sqlparser.DorisSqlParser

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

private object PipesExecuteInterceptor {

    fun handle(e: AnActionEvent, option: DatabaseSettings.ExecOption): Boolean {
        val console = JdbcConsole.findConsole(e) ?: return false
        if (!DorisPipes.isEnabled(console.project)) return false
        if (console.session.connectionPoint.dbms !== DorisDbms.DORIS) return false
        val editor = editorForPipeExecution(e) ?: return false
        val documentText = editor.document.text
        val current = DorisPipes.chunkAt(documentText, editor.caretModel.offset)
        val candidate = editor.selectionModel.selectedText ?: when (option.execInside) {
            DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT -> documentText
            DatabaseSettings.EXECUTE_INSIDE_SCRIPT_TAIL -> documentText.substring(current?.startOffset ?: editor.caretModel.offset)
            else -> current?.text ?: return false
        }
        if (!DorisPipes.containsPipeOperator(candidate)) return false
        val boundary = if (editor.selectionModel.hasSelection()) {
            DorisPipes.chunks(candidate).firstOrNull { it.hasPipeOperator && it.boundaryError != null }
        } else {
            current
                ?.takeIf { it.hasPipeOperator && it.boundaryError != null }
        }
        if (boundary?.boundaryError != null) {
            notifyBoundaryError(console, boundary.boundaryError)
            return true
        }

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
     * the previous action. False is reserved for unclaimed input. Exceptions propagate to the
     * IDE; neither cancellation nor a failed claimed request grants permission for raw fallback.
     */
    fun handle(console: JdbcConsole?, info: JdbcConsoleProvider.Info): Boolean {
        if (console == null || !DorisPipes.isEnabled(console.project)) return false
        val session = console.session
        if (session.connectionPoint.dbms !== DorisDbms.DORIS) return false
        val editor = info.editor ?: return false
        if (info.model.statements().none { DorisPipes.containsPipeOperator(it.query()) }) return false

        JdbcConsoleProvider.chooseStatements(info, "Nothing to run", true) { selected ->
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
                DorisPipesExecution.track(console, model.executionPlans)
                try {
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

private fun requirePipeTranslation(text: String): DorisPipesEngine.Transpile.Ok {
    return when (val result = DorisPipesEngine.transpile(text)) {
        is DorisPipesEngine.Transpile.Ok -> {
            result.executionError?.let { throw PipeTranslationFailure(it) }
            result
        }
        is DorisPipesEngine.Transpile.Err -> throw PipeTranslationFailure(result)
        DorisPipesEngine.Transpile.NotPipe -> throw PipeTranslationFailure(
            DorisPipesEngine.Transpile.Err(null, null, "PIPE statement was not translated: $text"),
        )
    }
}

internal class PipePlan<E>(
    val sourceQuery: String,
    val sourceText: String,
    val range: TextRange,
    val rangeOffset: Long,
    val type: com.intellij.psi.tree.IElementType,
    val api: com.intellij.psi.SyntaxTraverser.Api<E>,
    val value: E,
    val externals: List<ScriptModel.ExternalIt<E>>,
    val anchor: PipeAnchor,
    val parameters: List<ScriptModel.ParamIt<E>>,
    initial: DorisPipesEngine.Transpile.Ok,
) {
    @Volatile var translation: DorisPipesEngine.Transpile.Ok = initial
}

internal class PipeScriptModel<E>(
    private val delegate: ScriptModel<E>,
    private val editor: Editor,
) : ScriptModel<E>() {
    private val delegateParameters = snapshotParameters(delegate.parameters())
    val plans: List<PipePlan<E>> = delegate.statements().mapNotNull { statement ->
        val text = statement.query()
        if (!DorisPipes.containsPipeOperator(text)) return@mapNotNull null
        val translation = requirePipeTranslation(text)
        val offset = statement.rangeOffset().coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val range = statement.range().shiftRight(offset)
        val trimAnchor = range.startOffset + (text.length - text.trimStart().length)
        PipePlan(
            text,
            statement.text(),
            statement.range(),
            statement.rangeOffset(),
            statement.type(),
            statement.api(),
            statement.`object`(),
            snapshotExternals(statement.externals()),
            PipeAnchor(editor, range, delegate.virtualFile, trimAnchor, editor.document.text.hashCode()),
            (snapshotParameters(statement.parameters()) + pipeParameters(statement, text))
                .distinctBy { it.name() to it.range() },
            translation,
        )
    }.toList()
    private data class StatementKey(val range: TextRange, val rangeOffset: Long, val query: String)
    private fun key(statement: ScriptModel.StatementIt<E>) =
        StatementKey(statement.range(), statement.rangeOffset(), statement.query())
    private val byStatement = plans.associateBy { StatementKey(it.range, it.rangeOffset, it.sourceQuery) }
    val executionPlans: List<PipePlan<E>?> = delegate.statements().map { byStatement[key(it)] }.toList()

    fun translated(storage: ScriptModel.PStorage): DorisPipesEngine.Transpile.Ok {
        val plan = plans.single()
        PipeStatement(plan).consoleQuery(storage, Conditions.alwaysFalse())
        return plan.translation
    }

    override fun isActual(): Boolean = delegate.isActual
    override fun subModel(range: TextRange?): ScriptModel<E> = PipeScriptModel(delegate.subModel(range), editor)
    override fun everything(): JBIterable<E> = delegate.everything()
    override fun statements(): JBIterable<out ScriptModel.StatementIt<E>> = delegate.statements().transform { statement ->
        byStatement[key(statement)]?.let { PipeStatement(it) } ?: statement
    }
    override fun parameters(): JBIterable<out ScriptModel.ParamIt<E>> =
        JBIterable.from((delegateParameters + plans.flatMap { it.parameters }).distinctBy { it.name() to it.range() })
    override fun externals(): JBIterable<out ScriptModel.ExternalIt<E>> = delegate.externals()
    override fun getVirtualFile(): com.intellij.openapi.vfs.VirtualFile = delegate.virtualFile
    override fun getTextRange(): TextRange? = delegate.textRange
    override fun getLanguage(): com.intellij.lang.Language = delegate.language
    override fun <EE : Any?> rawTransform(
        transform: com.intellij.util.Function<in com.intellij.psi.SyntaxTraverser<E>, out com.intellij.psi.SyntaxTraverser<EE>>,
    ): ScriptModel<EE> = PipeScriptModel(delegate.rawTransform(transform), editor)

    private inner class PipeStatement(private val plan: PipePlan<E>) : ScriptModel.StatementIt<E> {
        override fun query(): String = plan.translation.dorisSql
        override fun consoleQuery(
            storage: ScriptModel.PStorage,
            condition: Condition<in ScriptModel.ParamIt<E>>,
        ): String {
            val translated = try {
                requirePipeTranslation(ScriptModelUtilCore.statementText(this, storage, condition))
            } catch (failure: PipeTranslationFailure) {
                throw TranslateException("Doris Pipes: ${failure.error.message}", failure)
            }
            plan.translation = translated
            return translated.dorisSql
        }
        override fun parameters(): JBIterable<out ScriptModel.ParamIt<E>> = JBIterable.from(plan.parameters)
        override fun externals(): JBIterable<out ScriptModel.ExternalIt<E>> = JBIterable.from(plan.externals)
        override fun text(): String = plan.sourceText
        override fun range(): TextRange = plan.range
        override fun type(): com.intellij.psi.tree.IElementType = plan.type
        override fun api(): com.intellij.psi.SyntaxTraverser.Api<E> = plan.api
        override fun rangeOffset(): Long = plan.rangeOffset
        override fun `object`(): E = plan.value
    }
}

private fun <E> snapshotParameters(
    parameters: Iterable<ScriptModel.ParamIt<E>>,
): List<ScriptModel.ParamIt<E>> = parameters.map { parameter ->
    val name = parameter.name()
    val description = parameter.description().toList()
    val text = parameter.text()
    val range = parameter.range()
    val type = parameter.type()
    val api = parameter.api()
    val rangeOffset = parameter.rangeOffset()
    val value = parameter.`object`()
    object : ScriptModel.ParamIt<E> {
        override fun name(): String = name
        override fun description(): Iterable<String> = description
        override fun text(): String = text
        override fun range(): TextRange = range
        override fun type(): com.intellij.psi.tree.IElementType = type
        override fun api(): com.intellij.psi.SyntaxTraverser.Api<E> = api
        override fun rangeOffset(): Long = rangeOffset
        override fun `object`(): E = value
    }
}

private fun <E> snapshotExternals(
    externals: Iterable<ScriptModel.ExternalIt<E>>,
): List<ScriptModel.ExternalIt<E>> = externals.map { external ->
    val text = external.text()
    val range = external.range()
    val type = external.type()
    val api = external.api()
    val rangeOffset = external.rangeOffset()
    val value = external.`object`()
    object : ScriptModel.ExternalIt<E> {
        override fun text(): String = text
        override fun range(): TextRange = range
        override fun type(): com.intellij.psi.tree.IElementType = type
        override fun api(): com.intellij.psi.SyntaxTraverser.Api<E> = api
        override fun rangeOffset(): Long = rangeOffset
        override fun `object`(): E = value
    }
}

private fun <E> pipeParameters(
    statement: ScriptModel.StatementIt<E>,
    text: String,
): List<ScriptModel.ParamIt<E>> {
    val tokens = DorisSqlParser().newLexer(text).allTokens.filter { it.channel == Token.DEFAULT_CHANNEL }
    val statementStart = statement.range().startOffset
    val statementType = statement.type()
    val statementApi = statement.api()
    val statementOffset = statement.rangeOffset()
    val statementValue = statement.`object`()
    return tokens.zipWithNext().mapNotNull { (colon, name) ->
        if (colon.type != DorisLexer.COLON || name.type != DorisLexer.IDENTIFIER ||
            colon.stopIndex + 1 != name.startIndex
        ) {
            return@mapNotNull null
        }
        val localStart = text.offsetByCodePoints(0, colon.startIndex)
        if (localStart > 0 && text[localStart - 1] == ':') return@mapNotNull null
        val localEnd = text.offsetByCodePoints(localStart, name.stopIndex + 1 - colon.startIndex)
        object : ScriptModel.ParamIt<E> {
            override fun name(): String = name.text
            override fun description(): Iterable<String> = emptyList()
            override fun text(): String = text.substring(localStart, localEnd)
            override fun range(): TextRange = TextRange(statementStart + localStart, statementStart + localEnd)
            override fun type(): com.intellij.psi.tree.IElementType = statementType
            override fun api(): com.intellij.psi.SyntaxTraverser.Api<E> = statementApi
            override fun rangeOffset(): Long = statementOffset
            override fun `object`(): E = statementValue
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
                "PIPE translation failed$where",
                pipeNotificationHtml(err.message),
                NotificationType.ERROR,
            )
            .notify(console.project)
    }

    /** Submit a fixed-scope helper request through the exact console that initiated it. */
    fun submit(
        console: JdbcConsole,
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
            console, request, dorisSql, originalText, transpile.takeIf { exactSourceMap },
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
        val originalText: String,
        val transpile: dev.brikk.house.sql.shape.TranspileResult?,
        val markFile: com.intellij.openapi.vfs.VirtualFile?,
        val markAnchor: Int,
        val markDocHash: Int,
    )
    private val runs = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, PipeRun>())
    private val audited = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
    private data class ExecutionSlot(val plan: PipePlan<*>?)
    private class TrackedExecution(
        val console: JdbcConsole,
        plans: List<PipePlan<*>?>,
    ) {
        val slots = plans.map(::ExecutionSlot)
    }
    private val trackedExecutionKey = Key.create<TrackedExecution>("DorisPipes.trackedExecution")
    private val startingExecution = ThreadLocal<TrackedExecution?>()

    fun track(console: JdbcConsole, plans: List<PipePlan<*>?>) {
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
        originalText: String,
        transpile: dev.brikk.house.sql.shape.TranspileResult?,
        markFile: com.intellij.openapi.vfs.VirtualFile?,
        markAnchor: Int,
        markDocHash: Int,
    ) {
        runs[request] = PipeRun(console, dorisSql, originalText, transpile, markFile, markAnchor, markDocHash)
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
                    if (plan.translation.dorisSql != query) return
                    val text = plan.sourceQuery
                    val anchor = plan.anchor
                    anchor.file?.let { DorisPipes.clearExecMark(execution.console.project, it.url) }
                    runs[request] = PipeRun(
                        execution.console,
                        query,
                        text,
                        plan.translation.result.takeIf { plan.parameters.isEmpty() },
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
                runPipeCatching {
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(run.console.project).restart()
                }
            }
        }
    }
}
