package dev.sort.doris.pipes

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import dev.sort.doris.sql.DorisSqlDialect
import java.awt.Dimension
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Alt+Enter intentions for the Doris Pipes SPIKE (see [DorisPipes]) — both available whenever the
 * caret sits inside a pipe statement of a Doris file:
 *
 *  - [PreviewPipeSqlIntention]: show the canonical Doris SQL the pipe program transpiles to,
 *    WITHOUT running it — the IDEAS §3 "Show generated Doris SQL" trust surface.
 *  - [RunPipesToStageIntention]: transpile + execute only the stages up to and including the one
 *    under the caret — the IDEAS §3 "Execute up to stage N" incremental-authoring feature (a
 *    prefix of pipe stages is itself a valid pipe program). Requires the file's console.
 */
private fun pipeChunkAtCaret(file: PsiFile, editor: Editor): DorisPipes.Chunk? {
    if (!DorisPipes.enabled || !file.language.isKindOf(DorisSqlDialect.INSTANCE)) return null
    val chunk = DorisPipes.chunkAt(editor.document.text, editor.caretModel.offset) ?: return null
    return chunk.takeIf { it.text.contains(DorisPipes.MARKER) }
}

/** The running console attached to exactly this file (matched via the session client's file). */
private fun consoleFor(project: Project, file: PsiFile): JdbcConsole? {
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

class PreviewPipeSqlIntention : IntentionAction {
    override fun getText(): String = "Doris Pipes: preview generated SQL"
    override fun getFamilyName(): String = "Doris Pipes"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && pipeChunkAtCaret(file, editor) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
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

    private fun showSqlPopup(editor: Editor, title: String, sql: String) {
        val area = JTextArea(sql).apply {
            isEditable = false
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, font.size)
        }
        val scroll = JScrollPane(area).apply { preferredSize = Dimension(640, 360.coerceAtMost(80 + 18 * sql.lines().size)) }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, area)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .showInBestPositionFor(editor)
    }
}

class RunPipesToStageIntention : IntentionAction {
    override fun getText(): String = "Doris Pipes: run stages up to caret"
    override fun getFamilyName(): String = "Doris Pipes"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && pipeChunkAtCaret(file, editor) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
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
                if (DorisPipesExecution.submit(console, result.dorisSql, prefix.text)) {
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
}
