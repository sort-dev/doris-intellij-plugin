package dev.sort.doris.plan

import com.intellij.database.actions.ExplainActionBase
import com.intellij.database.console.JdbcConsole
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sort.doris.DorisDbms
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants

/**
 * Overrides `Console.Jdbc.ExplainPlan` (`overrides="true"` in plugin.xml) so that, for Doris, the
 * "Explain Plan" action runs `EXPLAIN <query>` and shows the plan text in a scrollable popup.
 *
 * DataGrip's stock graphical Explain Plan is a node-diagram built for typed plan nodes. Doris
 * `EXPLAIN` returns a hierarchical text plan (one line per row) which that diagram mangles into
 * "Operation(<line>)" boxes with the indentation lost (see [DorisExplainPlanProvider]), and there
 * is no raw path to fall back on (`database.explain.plan.new.ui` gates it off). Rather than write a
 * Doris-specific typed-node parser, we run the `EXPLAIN` over a short-lived helper connection — the
 * exact pattern the cancel action uses — and render the plan text verbatim in a resizable popup
 * (monospaced, scrolls both ways, with Copy All + Close). The plan reads the way it does in the
 * Doris CLI, indentation intact.
 *
 * Only the DORIS branch is changed; every other dbms falls through to the stock graphical
 * [ExplainActionBase.Ui.Plan] behaviour via `super`.
 */
class DorisExplainPlanAction : ExplainActionBase.Ui.Plan() {

    override fun explainStatement(provider: ExplainPlanProvider, console: JdbcConsole, sql: String) {
        val session = console.session
        if (session.connectionPoint.dbms !== DorisDbms.DORIS) {
            super.explainStatement(provider, console, sql)
            return
        }
        val project = session.project
        // Match the console's current database so unqualified table names resolve the same way they
        // do in the console (best-effort — a qualified query or a data-source default still works).
        val namespace = try {
            console.currentNamespace?.name?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }
        // EXPLAIN runs off the EDT (helper connection + remote calls); the popup shows back on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val planText = runExplain(session.project, session.connectionPoint, namespace, sql)
            ApplicationManager.getApplication().invokeLater { showPlanPopup(project, planText) }
        }
    }

    /**
     * Run `EXPLAIN <sql>` over one short-lived helper connection (the cancel-action pattern) and
     * return the plan text — every row's first column joined by newlines. On any failure the
     * message is returned as the body so the user sees *why* rather than an empty window.
     */
    private fun runExplain(
        project: Project,
        connectionPoint: com.intellij.database.dataSource.DatabaseConnectionPoint,
        namespace: String?,
        sql: String,
    ): String {
        return try {
            val ref = DatabaseConnectionManager.getInstance()
                .build(project, connectionPoint)
                .setRequestor(ConnectionRequestor.Anonymous())
                .createBlockingNonCancellable()
                ?: return "Could not open a connection to run EXPLAIN."
            ref.use { r ->
                val helper = r.get()
                if (namespace != null) {
                    try {
                        execute(helper, "USE `$namespace`")
                    } catch (t: Throwable) {
                        LOG.info("Doris EXPLAIN: USE `$namespace` failed (continuing): ${t.message}")
                    }
                }
                val plan = queryPlanText(helper, "EXPLAIN $sql")
                plan.ifBlank { "EXPLAIN returned no rows." }
            }
        } catch (t: Throwable) {
            LOG.warn("Doris EXPLAIN failed: ${t.message}")
            "EXPLAIN failed:\n\n${t.message ?: t.toString()}"
        }
    }

    /** Every row's first column, joined by newlines — reconstructs Doris's line-per-row text plan. */
    private fun queryPlanText(connection: DatabaseConnection, sql: String): String {
        val statement = JdbcNativeUtil.computeRemote {
            connection.remoteConnection.createStatement()
        } ?: return ""
        try {
            val resultSet = JdbcNativeUtil.computeRemote { statement.executeQuery(sql) } ?: return ""
            try {
                return JdbcNativeUtil.computeRemote {
                    buildString {
                        while (resultSet.next()) {
                            append(resultSet.getString(1) ?: "")
                            append('\n')
                        }
                    }
                } ?: ""
            } finally {
                JdbcNativeUtil.performSafe { resultSet.close() }
            }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }

    private fun execute(connection: DatabaseConnection, sql: String) {
        val statement = JdbcNativeUtil.computeRemote {
            connection.remoteConnection.createStatement()
        } ?: throw IllegalStateException("could not create statement")
        try {
            JdbcNativeUtil.computeRemote { statement.execute(sql) }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }

    /**
     * Show the plan text in a resizable popup: a monospaced, read-only [JTextArea] that scrolls both
     * ways inside a [JBScrollPane], with a Copy All button and an obvious Close button (Esc and the
     * title-bar close work too).
     */
    private fun showPlanPopup(project: Project, planText: String) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val textArea = JTextArea(planText).apply {
            isEditable = false
            lineWrap = false
            font = Font(scheme.editorFontName, Font.PLAIN, scheme.editorFontSize)
            border = JBUI.Borders.empty(8)
            caretPosition = 0
        }
        val scroll = JBScrollPane(textArea).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(JBUI.scale(920), JBUI.scale(560))
        }

        val copyButton = JButton("Copy All")
        val closeButton = JButton("Close")
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(6))).apply {
            add(copyButton)
            add(closeButton)
        }

        val panel = JPanel(BorderLayout()).apply {
            add(scroll, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, textArea)
            .setTitle("Doris — Explain Plan")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(false)
            .setCancelKeyEnabled(true)
            .setMinSize(Dimension(JBUI.scale(480), JBUI.scale(280)))
            .createPopup()

        copyButton.addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(planText))
        }
        closeButton.addActionListener { popup.cancel() }

        popup.showCenteredInCurrentWindow(project)
    }

    private companion object {
        val LOG = Logger.getInstance(DorisExplainPlanAction::class.java)
    }
}
