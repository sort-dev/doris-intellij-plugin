package dev.sort.doris.sql

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.sort.doris.pipes.DorisPipes
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
class DorisErrorAnnotator : ExternalAnnotator<Pair<String, String>, List<DorisSyntaxError>>() {

    override fun collectInformation(file: PsiFile): Pair<String, String>? {
        if (!file.language.isKindOf(DorisSqlDialect.INSTANCE)) return null
        val text = file.text
        return if (text.isBlank()) null else file.viewProvider.virtualFile.url to text
    }

    override fun doAnnotate(collectedInfo: Pair<String, String>): List<DorisSyntaxError> {
        val (url, text) = collectedInfo
        val feErrors = validate(text)
        // PIPES SPIKE: pipe statements are foreign to fe-sql-parser by design, so its errors on
        // pipe chunks are noise — replace them with the ENGINE's verdict for those chunks (real
        // pipe syntax errors, absolute positions). Non-pipe chunks keep fe validation untouched.
        val base = if (!DorisPipes.enabled || !text.contains(DorisPipes.MARKER)) feErrors
        else runCatching {
            feErrors.filterNot { DorisPipes.lineInsidePipeChunk(text, it.line) } +
                DorisPipes.pipeSyntaxErrors(text)
        }.getOrDefault(feErrors)
        // PIPES SPIKE: last pipe run's SERVER error, squiggled at the exact mapped span (source-map
        // offsets); invalidated by any edit (doc-hash) or the next run for this file.
        val exec = if (!DorisPipes.enabled) emptyList() else runCatching {
            DorisPipes.execMarkFor(url, text)?.let { m ->
                val pre = text.substring(0, m.start.coerceIn(0, text.length))
                val line = pre.count { it == '\n' } + 1
                val col = m.start - (pre.lastIndexOf('\n') + 1)
                listOf(DorisSyntaxError(line, col, (m.end - m.start).coerceAtLeast(1), "Doris (server): ${m.message}"))
            }.orEmpty()
        }.getOrDefault(emptyList())
        return base + exec
    }

    override fun apply(file: PsiFile, annotationResult: List<DorisSyntaxError>, holder: AnnotationHolder) {
        if (annotationResult.isEmpty()) return
        val document = file.viewProvider.document ?: return
        for (error in annotationResult) {
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

            try {
                parser.multiStatements()           // console text is one or more ';'-separated statements
            } catch (t: Exception) {
                // DefaultErrorStrategy recovers; guard against any residual throw so highlighting never dies.
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
