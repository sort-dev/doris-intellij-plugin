package dev.sort.doris.pipes

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.script.ScriptModel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import dev.sort.doris.DorisDbms

/**
 * Replaces `Console.Jdbc.Execute` (`overrides="true"` in plugin.xml) — the transpile-on-run half
 * of the Doris Pipes SPIKE (see [DorisPipes]).
 *
 * Extends the stock [RunQueryAction.Alt1] and overrides ONLY the final `invokeImpl` overload, so
 * ALL stock behavior — statement-under-caret/selection resolution, multi-statement scripts,
 * parameters, enable-state — stays the platform's. Our hook fires after the platform has computed
 * the [ScriptModel] of what is about to run:
 *
 *  - Doris console + pipes flag on + the model text is a valid pipe program (engine verdict):
 *    transpile to canonical Doris SQL ([DorisPipes.transpile]) and submit THAT through the
 *    session's own request bus — `DataRequest.newRequest(consoleClient, sql, dbms)` into
 *    `session.messageBus.dataProducer.processRequest(...)` — so results land in the console's
 *    normal grid/output exactly like a stock run, and the OUTPUT LOG shows the generated Doris
 *    SQL (the §3 trust surface, for free).
 *  - Engine rejects a pipe-looking statement: error balloon with the engine's line/col — we do
 *    NOT fall through to stock (the server would only produce a worse error for pipe text).
 *  - Anything else (non-Doris, flag off, not a pipe program, any spike-path failure): stock
 *    execution, byte-for-byte.
 */
class DorisPipesRunQueryAction : RunQueryAction.Alt1() {

    override fun invokeImpl(
        console: JdbcConsole?,
        model: ScriptModel<*>,
        info: JdbcConsoleProvider.Info,
    ) {
        val handled = try {
            console != null && tryPipes(console, model, info)
        } catch (t: Throwable) {
            DorisPipes.warn("pipe execute path failed; falling back to stock: ${t.message}", t)
            false
        }
        if (!handled) super.invokeImpl(console, model, info)
    }

    private fun tryPipes(
        console: JdbcConsole,
        model: ScriptModel<*>,
        info: JdbcConsoleProvider.Info,
    ): Boolean {
        if (!DorisPipes.enabled) return false
        val session = console.session
        if (session.connectionPoint.dbms !== DorisDbms.DORIS) return false

        val document = info.editor?.document ?: return false
        val text = document.getText(model.textRange)
        if (!text.contains(DorisPipes.MARKER)) return false

        return when (val result = DorisPipes.transpile(text)) {
            is DorisPipes.Transpile.NotPipe -> false

            is DorisPipes.Transpile.Err -> {
                val where = result.line?.let { " (line ${result.line}, col ${result.col})" } ?: ""
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Doris Pipes")
                    .createNotification(
                        "Pipe program has a syntax error$where",
                        result.message,
                        NotificationType.ERROR,
                    )
                    .notify(console.project)
                true // handled: running the raw pipe text would only produce a worse server error
            }

            is DorisPipes.Transpile.Ok -> {
                val client = session.clientsWithFile.firstOrNull()
                    ?: return false // no attached console client — let stock produce its own error
                DorisPipes.info(
                    "session '${session.title}': pipe program (${text.length} chars) -> executing " +
                        "canonical Doris SQL (${result.dorisSql.length} chars)",
                )
                val request = DataRequest.newRequest(client, result.dorisSql, session.connectionPoint.dbms)
                session.messageBus.dataProducer.processRequest(request)
                true
            }
        }
    }
}
