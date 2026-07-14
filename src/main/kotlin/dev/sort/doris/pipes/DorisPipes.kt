package dev.sort.doris.pipes

import com.intellij.openapi.diagnostic.Logger
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.ParseError
import dev.sort.doris.sql.DorisSyntaxError

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

    val enabled: Boolean
        get() = isEnabledValue(System.getProperty(PROPERTY))

    /** Cheap textual pre-gate; the engine parse is the authority ([transpile]). */
    const val MARKER: String = "|>"

    const val LOG_PREFIX: String = "DorisPipes:"

    private val log = Logger.getInstance(DorisPipes::class.java)

    fun info(message: String) = log.info("$LOG_PREFIX $message")

    fun warn(message: String, t: Throwable? = null) =
        if (t != null) log.warn("$LOG_PREFIX $message", t) else log.warn("$LOG_PREFIX $message")

    // ---------------------------------------------------------------------------------------
    // Transpile (the execute-time payload)
    // ---------------------------------------------------------------------------------------

    sealed interface Transpile {
        /** A valid pipe program; [dorisSql] is the canonical Doris SQL to execute instead. */
        data class Ok(val dorisSql: String) : Transpile

        /** Pipe-looking but the engine rejects it; positions are 1-based like fe-sql-parser's. */
        data class Err(val line: Int?, val col: Int?, val message: String) : Transpile

        /** Parses fine but is NOT a pipe program (e.g. `|>` inside a string literal). */
        object NotPipe : Transpile
    }

    /**
     * Parse [text] as Doris (the engine's Doris dialect accepts pipe syntax natively), and if it
     * is a pipe program, desugar to canonical Doris SQL. Engine calls verified against brikk-sql
     * 0.5.1: parseOne -> PipeQuery, desugarPipes (explicit — plain transpile PRESERVES pipes),
     * Expression.sql("doris").
     */
    fun transpile(text: String): Transpile = try {
        val ast = Dialects.DORIS.parseOne(text.trim().removeSuffix(";"))
        if (ast !is PipeQuery) {
            Transpile.NotPipe
        } else {
            Transpile.Ok(desugarPipes(ast, false).sql("doris", pretty = true))
        }
    } catch (e: ParseError) {
        val first = e.errors.firstOrNull()
        Transpile.Err(first?.line, first?.col, first?.description ?: (e.message ?: "pipe parse error"))
    }

    // ---------------------------------------------------------------------------------------
    // Statement chunking (annotator support)
    // ---------------------------------------------------------------------------------------

    /**
     * A `;`-separated chunk of console text. [startLine] is the 1-based line of the chunk's FIRST
     * NON-WHITESPACE character — [transpile] trims leading blank lines, so the engine's relative
     * line 1 corresponds exactly to [startLine] (this is what makes error translation exact).
     */
    data class Chunk(val text: String, val startLine: Int, val endLine: Int)

    /** Naive `;` split preserving line numbers (see class KDoc for the spike-grade caveat). */
    fun chunks(text: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        val current = StringBuilder()
        var line = 1
        var firstContentLine = -1
        fun flush(endLine: Int) {
            if (current.isNotBlank()) {
                out.add(Chunk(current.toString(), if (firstContentLine == -1) endLine else firstContentLine, endLine))
            }
            current.setLength(0)
            firstContentLine = -1
        }
        for (ch in text) {
            current.append(ch)
            if (firstContentLine == -1 && !ch.isWhitespace()) firstContentLine = line
            if (ch == ';') flush(line)
            if (ch == '\n') line++
        }
        flush(line)
        return out
    }

    /** True when 1-based [line] falls inside a chunk that carries the pipe marker. */
    fun lineInsidePipeChunk(text: String, line: Int): Boolean =
        chunks(text).any { it.text.contains(MARKER) && line in it.startLine..it.endLine }

    /**
     * The engine's own syntax verdict for every pipe chunk of [text], as [DorisSyntaxError]s with
     * ABSOLUTE (whole-document) 1-based lines — drop-in replacements for the fe-sql-parser errors
     * the annotator suppresses on those chunks. Engine failures degrade to "no errors" (never let
     * a spike path kill highlighting).
     */
    fun pipeSyntaxErrors(text: String): List<DorisSyntaxError> {
        if (!text.contains(MARKER)) return emptyList()
        val out = ArrayList<DorisSyntaxError>()
        for (chunk in chunks(text)) {
            if (!chunk.text.contains(MARKER)) continue
            when (val r = runCatching { transpile(chunk.text) }.getOrElse { return emptyList() }) {
                is Transpile.Err -> {
                    val relLine = r.line ?: 1
                    out.add(
                        DorisSyntaxError(
                            line = chunk.startLine + relLine - 1,
                            col = (r.col ?: 1).coerceAtLeast(0),
                            length = 2,
                            message = "Doris Pipes: ${r.message}",
                        ),
                    )
                }
                else -> {} // Ok or NotPipe: nothing to report
            }
        }
        return out
    }
}
