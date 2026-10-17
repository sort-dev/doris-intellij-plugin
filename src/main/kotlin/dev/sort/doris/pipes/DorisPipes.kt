package dev.sort.doris.pipes

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.intellij.pipe.isEditingEnabled
import dev.brikk.house.sql.intellij.pipe.isEnabled
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.SqlScriptParameters

internal inline fun <T> runPipeCatching(action: () -> T): Result<T> = recoverEditorOperation(action)

object DorisPipes {
    const val PROPERTY = "doris.pipes"
    const val MARKER = "|>"
    const val LOG_PREFIX = "DorisPipes:"
    private val log = Logger.getInstance(DorisPipes::class.java)

    fun isEnabled(project: Project?): Boolean = project != null && DorisPipeDialect.isEnabled(project)
    fun isEditingEnabled(file: com.intellij.psi.PsiFile): Boolean = DorisPipeDialect.isEditingEnabled(file)
    fun containsPipeOperator(text: String): Boolean = text.contains(MARKER) && snapshot(text).statements.any { it.hasPipe }

    fun looksLikePipeChunk(text: String): Boolean {
        if (text.contains(MARKER)) return true
        val firstContent = text.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("--") } ?: return false
        return firstContent.startsWith("FROM ", ignoreCase = true) || firstContent.equals("FROM", ignoreCase = true)
    }

    fun info(message: String) = log.info("$LOG_PREFIX $message")
    fun warn(message: String, t: Throwable? = null) =
        if (t != null) log.warn("$LOG_PREFIX $message", t) else log.warn("$LOG_PREFIX $message")

    data class Chunk(
        val text: String,
        val startLine: Int,
        val endLine: Int,
        val startOffset: Int,
        val endOffset: Int,
        val boundaryError: String?,
        val hasPipeOperator: Boolean,
        val hasSql: Boolean,
    )

    private fun snapshot(text: String): SqlScriptSnapshot {
        ProgressManager.checkCanceled()
        return SqlScriptSnapshot.parse(text, "doris", ProgressManager::checkCanceled)
    }

    fun chunks(text: String): List<Chunk> {
        var line = 1
        return snapshot(text).statements.map { range ->
            val initialLine = line
            var firstContentLine: Int? = null
            for (index in range.start until range.end) {
                if (index and 1023 == 0) ProgressManager.checkCanceled()
                if (firstContentLine == null && !text[index].isWhitespace()) firstContentLine = line
                if (text[index] == '\n') line++
            }
            Chunk(text.substring(range.start, range.end), firstContentLine ?: initialLine,
                line, range.start, range.end, range.boundaryError, range.hasPipe, range.hasSql)
        }
    }

    fun chunkAt(text: String, offset: Int): Chunk? = chunks(text).let { chunks ->
        chunks.firstOrNull { offset >= it.startOffset && offset < it.endOffset }
            ?: chunks.lastOrNull()?.takeIf { offset == it.endOffset }
    }

    internal fun namedParameterRanges(text: String): List<IntRange> {
        ProgressManager.checkCanceled()
        return SqlScriptParameters.parse(text, "doris", ProgressManager::checkCanceled).occurrences
            .filter { it.style == ParamStyle.NAMED_COLON }.map { it.range.start until it.range.end }
    }

    internal fun codeBefore(text: String, endOffset: Int): String {
        ProgressManager.checkCanceled()
        val end = endOffset.coerceIn(0, text.length)
        val visible = CharArray(end) { ' ' }
        val tokens = Tokenizer(Dialects.forName("doris").tokenizerConfig)
            .tokenizeForEditor(text, ProgressManager::checkCanceled).tokens
        for (token in tokens) {
            if (token.tokenType == TokenType.STRING || token.tokenType.name.endsWith("_STRING")) continue
            if (token.tokenType == TokenType.IDENTIFIER && !SIMPLE_IDENTIFIER.matches(token.text)) continue
            for (index in token.start until (token.end + 1).coerceAtMost(end)) visible[index] = text[index]
        }
        return visible.concatToString()
    }

    private val SIMPLE_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    data class ExecMark(val start: Int, val end: Int, val message: String, val docHash: Int)
    fun setExecMark(project: Project, url: String, mark: ExecMark) {
        if (!project.isDisposed && isEnabled(project)) {
            val settings = project.service<DorisPipesSettings>()
            if (settings.execMarks.put(url, mark) != mark) diagnosticsChanged(project, url)
        }
    }
    fun clearExecMark(project: Project, url: String) {
        if (project.service<DorisPipesSettings>().execMarks.remove(url) != null) diagnosticsChanged(project, url)
    }
    private fun diagnosticsChanged(project: Project, url: String) {
        project.service<DorisPipesSettings>().diagnosticRevision.incrementAndGet()
        ApplicationManager.getApplication().invokeLater({
            val virtual = VirtualFileManager.getInstance().findFileByUrl(url) ?: return@invokeLater
            val file = PsiManager.getInstance(project).findFile(virtual) ?: return@invokeLater
            DaemonCodeAnalyzer.getInstance(project).restart(file, "Doris PIPE execution diagnostics changed")
        }, project.disposed)
    }
    fun execMarkFor(project: Project, url: String, currentText: String): ExecMark? =
        project.service<DorisPipesSettings>().execMarks[url]
            ?.takeIf { it.docHash == currentText.hashCode() && it.end <= currentText.length }

    data class MappedError(
        val originalLine: Int?, val transpiledLine: Int, val transpiledPos: Int,
        val startOffset: Int? = null, val endOffset: Int? = null,
    )
    internal val SERVER_POSITION = Regex("""\(line (\d+), pos (\d+)\)""")
}
