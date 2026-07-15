package dev.sort.doris.pipes

import com.intellij.openapi.diagnostic.Logger

/**
 * Doris Pipes SPIKE (branch `pipes-spike`; IDEAS-brikk-integration.md §3): author GoogleSQL
 * pipe syntax (`FROM t |> WHERE ... |> AGGREGATE ...`) in a Doris console and RUN it — the
 * statement is transpiled to canonical Doris SQL by the brikk-sql engine at execute time
 * ([DorisPipesRunQueryAction]), and the editor stops red-flagging pipe statements while showing
 * the engine's own (position-accurate) pipe syntax errors instead ([dev.sort.doris.sql.DorisErrorAnnotator]).
 *
 * Everything engine-facing lives in THIS file so the spike's engine dependency has one seam.
 * Verified engine baseline: brikk-sql >= 0.5.1 (see IDEAS §3 "Verified against 0.5.0/0.5.1").
 *
 * ## Spike-grade simplifications (fine for dogfood, not for shipping)
 *  - Statement chunking is a naive `;` split ([chunks]) — a `;` inside a string literal mis-splits
 *    (cosmetic only: it can mis-scope error suppression, never execution — execution uses the
 *    platform's own statement model).
 *  - Pipe detection is `text.contains("|>")` pre-gated, with the engine's parse as the authority.
 */
object DorisPipes {

    /** System property switch, same convention as catalogs/cancel: only an explicit "false" disables. */
    const val PROPERTY: String = "doris.pipes"

    fun isEnabledValue(raw: String?): Boolean = !"false".equals(raw, ignoreCase = true)

    /**
     * PATH B: the engine arrives via the OPTIONAL transpiler-plugin dependency
     * (`dev.sort.sql-transpiler-intellij-plugin`). When it isn't installed, every pipe feature
     * must vanish cleanly — [enabled] is the single gate all pipe code paths already check, so
     * it also requires the engine classes to be reachable through our classloader (checked once).
     * Auto-introspection is deliberately NOT behind this gate — it is engine-free.
     */
    val engineAvailable: Boolean by lazy {
        runCatching {
            Class.forName("dev.brikk.house.sql.shape.SqlFragment", false, DorisPipes::class.java.classLoader)
        }.isSuccess.also {
            if (!it) info("brikk-sql engine not present (transpiler plugin not installed) — pipe features disabled")
        }
    }

    val enabled: Boolean
        get() = engineAvailable && isEnabledValue(System.getProperty(PROPERTY))

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
    // Statement chunking (annotator support)
    // ---------------------------------------------------------------------------------------

    /**
     * A `;`-separated chunk of console text. [startLine] is the 1-based line of the chunk's FIRST
     * NON-WHITESPACE character — [transpile] trims leading blank lines, so the engine's relative
     * line 1 corresponds exactly to [startLine] (this is what makes error translation exact).
     * [startOffset]/[endOffset] are absolute document offsets (end exclusive) so the execute
     * interceptor can find the chunk under the caret WITHOUT the platform's statement PSI (which
     * the unmasked pipe syntax mangles — the spike's known statement-boundary limitation).
     */
    data class Chunk(
        val text: String,
        val startLine: Int,
        val endLine: Int,
        val startOffset: Int,
        val endOffset: Int,
    )

    /** Naive `;` split preserving line numbers + offsets (see class KDoc for the spike-grade caveat). */
    fun chunks(text: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        val current = StringBuilder()
        var line = 1
        var firstContentLine = -1
        var chunkStartOffset = 0
        fun flush(endLine: Int, endOffsetExclusive: Int) {
            if (current.isNotBlank()) {
                out.add(
                    Chunk(
                        text = current.toString(),
                        startLine = if (firstContentLine == -1) endLine else firstContentLine,
                        endLine = endLine,
                        startOffset = chunkStartOffset,
                        endOffset = endOffsetExclusive,
                    ),
                )
            }
            current.setLength(0)
            firstContentLine = -1
            chunkStartOffset = endOffsetExclusive
        }
        for ((i, ch) in text.withIndex()) {
            current.append(ch)
            if (firstContentLine == -1 && !ch.isWhitespace()) firstContentLine = line
            if (ch == ';') flush(line, i + 1)
            if (ch == '\n') line++
        }
        flush(line, text.length)
        return out
    }

    /** The chunk whose span contains document [offset] (a caret at a chunk's very end counts). */
    fun chunkAt(text: String, offset: Int): Chunk? =
        chunks(text).firstOrNull { offset >= it.startOffset && offset <= it.endOffset }


    // ---------------------------------------------------------------------------------------
    // Execution-error editor marks (squiggle at the mapped span; hover = server message)
    // ---------------------------------------------------------------------------------------

    /** Absolute-document-offset span + message; [docHash] invalidates the mark on ANY edit. */
    data class ExecMark(val start: Int, val end: Int, val message: String, val docHash: Int)

    private val execMarks = java.util.Collections.synchronizedMap(HashMap<String, ExecMark>())

    fun setExecMark(url: String, mark: ExecMark) { execMarks[url] = mark }

    fun clearExecMark(url: String) { execMarks.remove(url) }

    /** The current mark for [url], or null when the document changed since the run. */
    fun execMarkFor(url: String, currentText: String): ExecMark? =
        execMarks[url]?.takeIf { it.docHash == currentText.hashCode() && it.end <= currentText.length }

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
