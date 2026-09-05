package dev.sort.doris.pipes

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.antlr.v4.runtime.Token
import org.apache.doris.nereids.DorisLexer
import org.apache.doris.sqlparser.DorisSqlParser

/**
 * Doris Pipes SPIKE (branch `pipes-spike`; IDEAS-brikk-integration.md §3): author GoogleSQL
 * pipe syntax (`FROM t |> WHERE ... |> AGGREGATE ...`) in a Doris console and RUN it — the
 * statement is transpiled to canonical Doris SQL by the brikk-sql engine at execute time
 * ([DorisPipesRunQueryAction]), and the editor stops red-flagging pipe statements while showing
 * the engine's own (position-accurate) pipe syntax errors instead ([dev.sort.doris.sql.DorisErrorAnnotator]).
 *
 * Engine-facing code lives in [DorisPipesEngine]; the shared engine is bundled with this plugin.
 *
 * Statement ranges use the bundled Doris lexer, independently of project enablement. Execution,
 * preview, completion, and syntax diagnostics share these ranges. Pipe detection still uses a
 * textual pre-gate, with the engine's parse as the authority at execution time.
 */
object DorisPipes {

    /** Retained emergency veto. A true value never enables an unchecked project setting. */
    const val PROPERTY: String = "doris.pipes"

    fun isEnabledValue(raw: String?): Boolean = !"false".equals(raw, ignoreCase = true)

    fun isEnabled(project: Project?): Boolean = project != null && !project.isDisposed && !project.isDefault &&
        isEnabledValue(System.getProperty(PROPERTY)) && project.service<DorisPipesSettings>().enabled

    /** Cheap textual pre-gate; the engine parse is the authority ([transpile]). */
    const val MARKER: String = "|>"

    /**
     * A chunk counts as pipe territory when it carries `|>` OR its first content word is FROM —
     * a bare `FROM ...` chunk is a pipe program being authored (plain Doris SQL never starts a
     * statement with FROM), and completion must work BEFORE the first `|>` is typed.
     */
    fun looksLikePipeChunk(text: String): Boolean {
        if (text.contains(MARKER)) return true
        val firstContent = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("--") } ?: return false
        return firstContent.startsWith("FROM ", ignoreCase = true) ||
            firstContent.equals("FROM", ignoreCase = true)
    }

    const val LOG_PREFIX: String = "DorisPipes:"

    private val log = Logger.getInstance(DorisPipes::class.java)

    fun info(message: String) = log.info("$LOG_PREFIX $message")

    fun warn(message: String, t: Throwable? = null) =
        if (t != null) log.warn("$LOG_PREFIX $message", t) else log.warn("$LOG_PREFIX $message")


    // ---------------------------------------------------------------------------------------
    // Shared statement ranges
    // ---------------------------------------------------------------------------------------

    /**
     * An exact document slice, including leading trivia and its terminating semicolon, if any.
     * [startLine] is the 1-based line of the first non-whitespace character, matching the engine's
     * trimmed input. Offsets are UTF-16 document offsets, with [endOffset] exclusive.
     * [boundaryError] marks an unterminated lexical construct: the unresolved remainder stays in
     * one chunk and must not be executed as a guessed prefix.
     */
    data class Chunk(
        val text: String,
        val startLine: Int,
        val endLine: Int,
        val startOffset: Int,
        val endOffset: Int,
        val boundaryError: String?,
    )

    /** Split only on Doris delimiter tokens, never semicolons inside literals or comments. */
    fun chunks(text: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        val lexer = DorisSqlParser().newLexer(text)
        var line = 1
        var firstContentLine = -1
        var chunkStartOffset = 0
        var boundaryError: String? = null
        fun flush(endOffsetExclusive: Int) {
            if (firstContentLine != -1) {
                out.add(
                    Chunk(
                        text = text.substring(chunkStartOffset, endOffsetExclusive),
                        startLine = firstContentLine,
                        endLine = line,
                        startOffset = chunkStartOffset,
                        endOffset = endOffsetExclusive,
                        boundaryError = boundaryError,
                    ),
                )
            }
            firstContentLine = -1
            chunkStartOffset = endOffsetExclusive
        }
        var offset = 0
        var codePointOffset = 0
        while (true) {
            val token = lexer.nextToken()
            if (token.type == Token.EOF) break
            // ANTLR uses code-point indices; editors and String.substring use UTF-16 indices.
            val tokenEnd = token.stopIndex + 1
            var end = text.offsetByCodePoints(offset, tokenEnd - codePointOffset)
            boundaryError = when {
                token.type == DorisLexer.COMMENT_START || token.type == DorisLexer.HINT_START ->
                    "Unterminated SQL comment; cannot determine the complete statement."
                token.type == DorisLexer.BRACKETED_COMMENT -> {
                    // Recovery can close an outer comment at the inner comment's terminator.
                    var depth = 0
                    var i = offset
                    while (i < end - 1) {
                        when {
                            text.startsWith("/*", i) -> { depth++; i += 2 }
                            text.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                    if (depth != 0) "Unterminated nested SQL comment; cannot determine the complete statement."
                    else null
                }
                token.type == DorisLexer.UNRECOGNIZED &&
                    (token.text == "'" || token.text == "\"" || token.text == "`") ->
                    "Unterminated SQL string or quoted identifier; cannot determine the complete statement."
                token.type == DorisLexer.IDENTIFIER && token.text.startsWith("\$\$") ->
                    "Cannot determine the complete statement at \$\$: close the dollar-quoted string " +
                        "or backtick-quote the identifier."
                else -> null
            }
            // The lexer recovers unclosed constructs as ordinary tokens. Do not let semicolons
            // in that recovery stream create an apparently valid, truncated executable chunk.
            if (boundaryError != null) end = text.length
            for (i in offset until end) {
                if (firstContentLine == -1 && !text[i].isWhitespace()) firstContentLine = line
                if (text[i] == '\n') line++
            }
            offset = end
            codePointOffset = tokenEnd
            if (boundaryError != null) break
            if (token.type == DorisLexer.SEMICOLON) flush(end)
        }
        flush(text.length)
        return out
    }

    /** Half-open membership; just after the last statement's terminator still belongs to that statement. */
    fun chunkAt(text: String, offset: Int): Chunk? {
        if (offset !in 0..text.length) return null
        val chunks = chunks(text)
        return chunks.firstOrNull { offset >= it.startOffset && offset < it.endOffset }
            ?: chunks.lastOrNull()?.takeIf { offset == it.endOffset }
    }


    // ---------------------------------------------------------------------------------------
    // Execution-error editor marks (squiggle at the mapped span; hover = server message)
    // ---------------------------------------------------------------------------------------

    /** Absolute-document-offset span + message; [docHash] invalidates the mark on ANY edit. */
    data class ExecMark(val start: Int, val end: Int, val message: String, val docHash: Int)

    fun setExecMark(project: Project, url: String, mark: ExecMark) {
        if (!project.isDisposed) project.service<DorisPipesSettings>().execMarks[url] = mark
    }

    fun clearExecMark(project: Project, url: String) { project.service<DorisPipesSettings>().execMarks.remove(url) }

    /** The current mark for [url], or null when the document changed since the run. */
    fun execMarkFor(project: Project, url: String, currentText: String): ExecMark? =
        project.service<DorisPipesSettings>().execMarks[url]
            ?.takeIf { it.docHash == currentText.hashCode() && it.end <= currentText.length }

    // ---------------------------------------------------------------------------------------
    // Server-error map-back (MVP: token-text heuristic; real fix = generated-position provenance)
    // ---------------------------------------------------------------------------------------

    /** A Doris server error against the TRANSPILED SQL, mapped back toward the pipe original. */
    data class MappedError(
        val token: String?,
        val originalLine: Int?,
        val transpiledLine: Int,
        val transpiledPos: Int,
        /** Chunk-relative 0-based char span in the ORIGINAL pipe text (engine map; exact path only). */
        val startOffset: Int? = null,
        val endOffset: Int? = null,
    )



    internal val SERVER_POSITION = Regex("""\(line (\d+), pos (\d+)\)""")
    private val IDENT_AT = Regex("""[A-Za-z_`][A-Za-z0-9_$]*""")

    /**
     * Map a Doris error message carrying `(line N, pos M)` — positions in [transpiledSql], which is
     * what the server actually ran — back to a 1-based line of the ORIGINAL pipe text.
     *
     * MVP heuristic: extract the identifier at the reported transpiled position and find the line
     * of its first occurrence in [originalText] (exact first, case-insensitive second). Ambiguous
     * (repeated) tokens map to their first occurrence — good enough for a balloon hint. The exact
     * fix is generated-position provenance (re-parse the generated SQL and zip its node meta with
     * the desugared AST's original-position meta), queued behind the spike verdict.
     */
    fun mapServerError(message: String, transpiledSql: String, originalText: String): MappedError? {
        val match = SERVER_POSITION.find(message) ?: return null
        val line = match.groupValues[1].toInt()
        val pos = match.groupValues[2].toInt()
        val transpiledLine = transpiledSql.lines().getOrNull(line - 1)
            ?: return MappedError(null, null, line, pos)
        val token = IDENT_AT.find(transpiledLine, pos.coerceIn(0, transpiledLine.length))?.value
            ?: return MappedError(null, null, line, pos)
        val originalLines = originalText.lines()
        val exact = originalLines.indexOfFirst { it.contains(token) }
        val found = if (exact >= 0) exact else originalLines.indexOfFirst { it.contains(token, ignoreCase = true) }
        return MappedError(token, if (found >= 0) found + 1 else null, line, pos)
    }

    /** True when 1-based [line] falls inside a chunk that carries the pipe marker. */
    fun lineInsidePipeChunk(text: String, line: Int): Boolean =
        chunks(text).any { it.text.contains(MARKER) && line in it.startLine..it.endLine }

}
