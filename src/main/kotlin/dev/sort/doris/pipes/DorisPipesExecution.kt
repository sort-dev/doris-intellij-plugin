package dev.sort.doris.pipes

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.connection.throwable.info.ErrorInfo
import com.intellij.database.console.JdbcConsole
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataProducer
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.run.ConsoleDataRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import dev.brikk.house.sql.intellij.SqlExecutionRefusal
import dev.brikk.house.sql.intellij.pipe.PipeRun
import dev.brikk.house.sql.intellij.pipe.PipeSubmitListener
import java.util.Collections
import java.util.WeakHashMap

internal object DorisPipesExecution : PipeSubmitListener {
    private class TrackedExecution(val run: PipeRun) {
        val file = FileDocumentManager.getInstance().getFile(run.editor.document)
        val documentHash = run.editor.document.text.hashCode()
    }

    private class AuditedRun(
        val console: JdbcConsole,
        val source: String,
        val sourceMap: ((Int, Int) -> TextRange?)?,
        val file: VirtualFile?,
        val anchor: Int,
        val documentHash: Int,
    )

    private val runs = Collections.synchronizedMap(WeakHashMap<DataRequest, AuditedRun>())
    private val audited = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val trackedExecutionKey = Key.create<TrackedExecution>("DorisPipes.trackedExecution")
    private val startingExecution = ThreadLocal<TrackedExecution?>()

    override fun beforeSubmit(run: PipeRun) {
        if (startingExecution.get() != null) throw SqlExecutionRefusal("Nested PIPE execution tracking is not supported")
        startingExecution.set(TrackedExecution(run))
        try {
            ensureAuditor(run.console)
        } catch (failure: Throwable) {
            startingExecution.remove()
            throw failure
        }
    }

    override fun afterSubmit(run: PipeRun) {
        if (startingExecution.get()?.run === run) startingExecution.remove()
    }

    private fun ensureAuditor(console: JdbcConsole) {
        val bus = console.session.messageBus
        synchronized(audited) {
            if (audited.containsKey(bus)) return
            bus.addAuditor(object : DataAuditor {
                override fun jobSubmitted(request: DataRequest, producer: DataProducer) {
                    val consoleRequest = request as? ConsoleDataRequest ?: return
                    val shared = consoleRequest.sharedDataHolder
                    val execution = shared.getUserData(trackedExecutionKey)
                        ?: startingExecution.get()?.takeIf { it.run.console === request.owner }?.also {
                            shared.putUserData(trackedExecutionKey, it)
                        }
                        ?: return
                    val statement = execution.run.statements.getOrNull(consoleRequest.queryIndex) ?: return
                    if (statement.sql != consoleRequest.query) return
                    execution.file?.let { DorisPipes.clearExecMark(execution.run.console.project, it.url) }
                    runs[request] = AuditedRun(
                        execution.run.console, statement.source, statement.sourceMap, execution.file,
                        statement.range.startOffset, execution.documentHash,
                    )
                }

                override fun error(context: DataRequest.Context, info: ErrorInfo) {
                    val run = runs[context.request] ?: return
                    runPipeCatching { balloonMappedError(run, info) }
                }
            })
            audited[bus] = true
        }
    }

    private fun balloonMappedError(run: AuditedRun, info: ErrorInfo) {
        val message = runPipeCatching { info.message }.getOrNull() ?: return
        val mapped = mappedPipeError(message, run.source, run.sourceMap) ?: return
        if (mapped.originalLine == null) return
        NotificationGroupManager.getInstance().getNotificationGroup(DorisPipeDialect.notificationGroup)
            .createNotification(
                DorisPipeDialect.label,
                "SQL execution error at line ${mapped.originalLine}\n" +
                    "For the translated SQL, the error was reported as:\n$message\n" +
                    "(right-click → Doris PIPE → Preview Generated SQL)",
                NotificationType.WARNING,
            ).notify(run.console.project)
        val file = run.file
        if (file != null && mapped.startOffset != null && mapped.endOffset != null) {
            DorisPipes.setExecMark(run.console.project, file.url, DorisPipes.ExecMark(
                run.anchor + mapped.startOffset, run.anchor + mapped.endOffset + 1, message, run.documentHash,
            ))
            ApplicationManager.getApplication().invokeLater {
                runPipeCatching {
                    DaemonCodeAnalyzer.getInstance(run.console.project).restart("Doris PIPE server error mapped")
                }
            }
        }
    }
}

internal fun mappedPipeError(message: String, source: String, map: ((Int, Int) -> TextRange?)?): DorisPipes.MappedError? {
    if (map == null) return null
    val match = DorisPipes.SERVER_POSITION.find(message) ?: return null
    val line = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val position = match.groupValues[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val range = map(line, position) ?: return null
    return DorisPipes.MappedError(1 + source.take(range.startOffset).count { it == '\n' },
        line, position, range.startOffset, range.endOffset - 1)
}
