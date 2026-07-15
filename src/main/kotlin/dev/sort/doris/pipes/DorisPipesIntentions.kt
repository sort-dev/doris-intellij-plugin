package dev.sort.doris.pipes

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import dev.sort.doris.sql.DorisSqlDialect
import java.awt.Dimension
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * User-facing surfaces for the Doris Pipes SPIKE (see [DorisPipes]), all gated on the caret being
 * inside a pipe statement of a Doris file:
 *
 *  - Alt+Enter intentions ([PreviewPipeSqlIntention], [RunPipesToStageIntention]);
 *  - the editor right-click "Doris Pipes" submenu ([PreviewPipeSqlAction],
 *    [RunPipesToStageAction]) — same operations, discoverable without the intention machinery.
 *
 * Operations:
 *  - PREVIEW: show the canonical Doris SQL the pipe program transpiles to, WITHOUT running it —
 *    the IDEAS §3 "Show generated Doris SQL" trust surface.
 *  - RUN TO STAGE: transpile + execute only the stages up to and including the one under the
 *    caret (§3 "Execute up to stage N"; a prefix of pipe stages is itself a valid pipe program).
 */
internal object DorisPipesUi {

    fun pipeChunkAtCaret(file: PsiFile, editor: Editor): DorisPipes.Chunk? {
        if (!DorisPipes.enabled || !file.language.isKindOf(DorisSqlDialect.INSTANCE)) return null
        val chunk = DorisPipes.chunkAt(editor.document.text, editor.caretModel.offset) ?: return null
        return chunk.takeIf { it.text.contains(DorisPipes.MARKER) }
    }

    /** The running console attached to exactly this file (matched via the session client's file). */
    fun consoleFor(project: Project, file: PsiFile): JdbcConsole? {
        val vf = file.viewProvider.virtualFile
        return JdbcConsoleProvider.getRunningConsoles(project).firstOrNull { console ->
            runCatching { console.session.clientsWithFile.any { it.virtualFile == vf } }.getOrDefault(false)
        }
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Doris Pipes")
            .createNotification(title, content, type)
            .notify(project)
    }

    fun preview(project: Project, editor: Editor, file: PsiFile) {
        val chunk = pipeChunkAtCaret(file, editor) ?: return
        when (val result = DorisPipes.transpile(chunk.text)) {
            is DorisPipes.Transpile.Ok -> showSqlPopup(editor, "Generated Doris SQL", result.dorisSql)
            is DorisPipes.Transpile.Err -> notify(
                project,
                "Pipe program has a syntax error" +
                    (result.line?.let { " (line ${result.line}, col ${result.col})" } ?: ""),
                result.message,
                NotificationType.ERROR,
            )
            is DorisPipes.Transpile.NotPipe ->
                notify(project, "Not a pipe program", "The statement parses as plain SQL.", NotificationType.INFORMATION)
        }
    }

    fun runToStage(project: Project, editor: Editor, file: PsiFile) {
        val chunk = pipeChunkAtCaret(file, editor) ?: return
        val console = consoleFor(project, file) ?: run {
            notify(
                project,
                "No running console for this file",
                "Attach/connect the console (run any statement once), then retry.",
                NotificationType.WARNING,
            )
            return
        }
        val rel = editor.caretModel.offset - chunk.startOffset
        val prefix = DorisPipes.stagePrefixAt(chunk.text, rel) ?: run {
            notify(project, "Could not split pipe stages", "See idea.log (DorisPipes:).", NotificationType.WARNING)
            return
        }
        when (val result = DorisPipes.transpile(prefix.text)) {
            is DorisPipes.Transpile.Ok -> {
                DorisPipes.info("run-to-stage: stage ${prefix.stage}/${prefix.totalStages}")
                val anchor = chunk.startOffset + (chunk.text.length - chunk.text.trimStart().length)
                if (DorisPipesExecution.submit(
                        console, result.dorisSql, prefix.text, result.result,
                        file.viewProvider.virtualFile, anchor, editor.document.text.hashCode(),
                    )
                ) {
                    notify(
                        project,
                        "Running pipe stages 1–${prefix.stage} of ${prefix.totalStages}",
                        "Results appear in the console grid; generated SQL in the output log.",
                        NotificationType.INFORMATION,
                    )
                }
            }
            is DorisPipes.Transpile.Err -> notify(
                project,
                "Stage prefix has a syntax error" +
                    (result.line?.let { " (line ${result.line}, col ${result.col})" } ?: ""),
                result.message,
                NotificationType.ERROR,
            )
            is DorisPipes.Transpile.NotPipe ->
                notify(project, "Not a pipe program", "The statement parses as plain SQL.", NotificationType.INFORMATION)
        }
    }

    private fun showSqlPopup(editor: Editor, title: String, sql: String) {
        val project = editor.project
        // DorisSQL-highlighted read-only editor component; JTextArea fallback if it can't build.
        val component: javax.swing.JComponent = runCatching {
            com.intellij.ui.LanguageTextField(DorisSqlDialect.INSTANCE, project, sql, false).apply {
                isViewer = true
                setCaretPosition(0)
            } as javax.swing.JComponent
        }.getOrElse {
            JTextArea(sql).apply {
                isEditable = false
                font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size)
            }
        }
        val scroll = JScrollPane(component).apply {
            preferredSize = Dimension(640, 360.coerceAtMost(80 + 18 * sql.lines().size))
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, component)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .showInBestPositionFor(editor)
    }
}

// ---------------------------------------------------------------------------------------------
// Alt+Enter intentions
// ---------------------------------------------------------------------------------------------

class PreviewPipeSqlIntention : IntentionAction {
    override fun getText(): String = "Doris Pipes: preview generated SQL"
    override fun getFamilyName(): String = "Doris Pipes"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && DorisPipesUi.pipeChunkAtCaret(file, editor) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null && file != null) DorisPipesUi.preview(project, editor, file)
    }
}

class RunPipesToStageIntention : IntentionAction {
    override fun getText(): String = "Doris Pipes: run stages up to caret"
    override fun getFamilyName(): String = "Doris Pipes"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && DorisPipesUi.pipeChunkAtCaret(file, editor) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null && file != null) DorisPipesUi.runToStage(project, editor, file)
    }
}

// ---------------------------------------------------------------------------------------------
// Editor right-click menu ("Doris Pipes" submenu) — same operations, intention-machinery-free
// ---------------------------------------------------------------------------------------------

abstract class DorisPipesMenuAction : AnAction() {
    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    final override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible =
            editor != null && file != null && DorisPipesUi.pipeChunkAtCaret(file, editor) != null
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        perform(project, editor, file)
    }

    abstract fun perform(project: Project, editor: Editor, file: PsiFile)
}

class PreviewPipeSqlAction : DorisPipesMenuAction() {
    override fun perform(project: Project, editor: Editor, file: PsiFile) =
        DorisPipesUi.preview(project, editor, file)
}

class RunPipesToStageAction : DorisPipesMenuAction() {
    override fun perform(project: Project, editor: Editor, file: PsiFile) =
        DorisPipesUi.runToStage(project, editor, file)
}
