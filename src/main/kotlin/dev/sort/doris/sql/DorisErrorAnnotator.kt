package dev.sort.doris.sql

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.sort.doris.pipes.DorisPipes
import dev.sort.doris.pipes.DorisPipesEngine
import dev.sort.doris.pipes.runPipeCatching
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.DefaultErrorStrategy
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.apache.doris.sqlparser.DorisSqlParser

/**
 * Doris-accurate syntax validation, layered on separately from the SQL92 editor parser
 * (whose own errors are suppressed by [DorisHighlightErrorFilter]). Parses the file text with
 * the authoritative Doris grammar (fe-sql-parser) off the EDT and reports every syntax error
 * at its real location — this is what makes genuine Doris DDL/DML mistakes light up.
 */
class DorisErrorAnnotator : ExternalAnnotator<DorisErrorAnnotator.Input, DorisErrorAnnotator.Result>() {
    data class Input(val text: String, val pipesEnabled: Boolean, val execMark: DorisPipes.ExecMark? = null)
    data class Result(val pipesEnabled: Boolean, val errors: List<DorisSyntaxError>)

    override fun collectInformation(file: PsiFile): Input? {
        if (!file.language.isKindOf(DorisSqlDialect.INSTANCE)) return null
        val text = file.text
        if (text.isBlank()) return null
        val enabled = DorisPipes.isEnabled(file.project)
        val mark = if (enabled) DorisPipes.execMarkFor(file.project, file.viewProvider.virtualFile.url, text) else null
        return Input(text, enabled, mark)
    }

    override fun doAnnotate(collectedInfo: Input): Result {
        val (text, pipesEnabled, execMark) = collectedInfo
        val feErrors = validate(text)
        // DORIS PIPES: pipe statements are foreign to fe-sql-parser by design, so its errors on
        // pipe chunks are noise — replace them with the ENGINE's verdict for those chunks (real
        // pipe syntax errors, absolute positions). Non-pipe chunks keep fe validation untouched.
        val base = if (!pipesEnabled || !text.contains(DorisPipes.MARKER)) feErrors
        else runPipeCatching {
            val pipeChunks = DorisPipes.chunks(text).filter { it.hasPipeOperator }
            feErrors.filterNot { error -> pipeChunks.any { error.line in it.startLine..it.endLine } } +
                DorisPipesEngine.pipeSyntaxErrors(text)
        }.getOrDefault(feErrors)
        // DORIS PIPES: last pipe run's SERVER error, squiggled at the exact mapped span (source-map
        // offsets); invalidated by any edit (doc-hash) or the next run for this file.
        val exec = if (!pipesEnabled) emptyList() else runPipeCatching {
            execMark?.let { m ->
                val pre = text.substring(0, m.start.coerceIn(0, text.length))
                val line = pre.count { it == '\n' } + 1
                val col = m.start - (pre.lastIndexOf('\n') + 1)
                listOf(DorisSyntaxError(line, col, (m.end - m.start).coerceAtLeast(1), "Doris (server): ${m.message}"))
            }.orEmpty()
        }.getOrDefault(emptyList())
        return Result(pipesEnabled, base + exec)
    }

    override fun apply(file: PsiFile, annotationResult: Result, holder: AnnotationHolder) {
        // A setting change can finish while the background pass is still running.
        if (annotationResult.pipesEnabled != DorisPipes.isEnabled(file.project)) return
        if (annotationResult.errors.isEmpty()) return
        val document = file.viewProvider.document ?: return
        for (error in annotationResult.errors) {
            val range = error.toTextRange(document) ?: continue
            holder.newAnnotation(HighlightSeverity.ERROR, error.message)
                .range(range)
                .create()
        }
    }

    private companion object {
        // Stateless + thread-safe per fe-sql-parser docs; safe to share across background threads.
        private val PARSER = DorisSqlParser()

        private fun validate(text: String): List<DorisSyntaxError> {
            val errors = ArrayList<DorisSyntaxError>()
            val collector = object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String,
                    e: RecognitionException?
                ) {
                    val length = (offendingSymbol as? Token)?.text?.length?.coerceAtLeast(1) ?: 1
                    errors.add(DorisSyntaxError(line, charPositionInLine, length, msg))
                }
            }

            val lexer = PARSER.newLexer(text)
            lexer.removeErrorListeners()          // drop fe-sql-parser's throwing listener
            lexer.addErrorListener(collector)

            val parser = PARSER.newParser(lexer)
            parser.removeErrorListeners()          // ditto — we collect instead of throwing on the first
            parser.addErrorListener(collector)
            parser.errorHandler = DefaultErrorStrategy() // recover past errors so we report all of them

            runPipeCatching {
                parser.multiStatements()           // console text is one or more ';'-separated statements
            }
            return errors
        }
    }
}

/** A single Doris syntax error at a 1-based [line] / 0-based [col], spanning [length] chars. */
data class DorisSyntaxError(val line: Int, val col: Int, val length: Int, val message: String) {
    fun toTextRange(document: Document): TextRange? {
        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        val start = (lineStart + col).coerceIn(lineStart, lineEnd)
        val end = (start + length).coerceIn(start, lineEnd)
        // Ensure a non-empty range (e.g. errors reported at end-of-line/EOF).
        return if (end > start) TextRange(start, end)
        else TextRange(start, (start + 1).coerceAtMost(document.textLength))
    }
}
