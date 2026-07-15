package dev.sort.doris.pipes

import com.intellij.openapi.diagnostic.Logger
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.TranspileResult
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
    // Transpile (the execute-time payload)
    // ---------------------------------------------------------------------------------------

    sealed interface Transpile {
        /** A valid pipe program; [dorisSql] is the canonical Doris SQL to execute instead.
         *  [result] carries the engine SourceMap (identity-tied to [dorisSql]) for exact
         *  server-error map-back; null only in unit-test fabrication. */
        data class Ok(val dorisSql: String, val result: TranspileResult? = null) : Transpile

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
        val fragment = SqlFragment(text.trim().removeSuffix(";"), "doris")
        if (fragment.ast !is PipeQuery) {
            Transpile.NotPipe
        } else {
            val result = fragment.toExecutable("doris", pretty = true)
            Transpile.Ok(result.sql, result)
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
    // Stage boundaries (execute-to-stage-N; IDEAS §3 "Execute up to stage N")
    // ---------------------------------------------------------------------------------------

    /** The pipe-program prefix ending at the stage containing [relOffset], plus (1-based stage, total). */
    data class StagePrefix(val text: String, val stage: Int, val totalStages: Int)

    /**
     * Cut [chunkText] (one pipe program) down to the stages up to and including the one containing
     * [relOffset] (an offset RELATIVE to [chunkText]). A prefix of pipe stages is itself a valid
     * pipe program, so the result transpiles/executes like any other. Uses the engine's own
     * [dev.brikk.house.sql.parser.PipeStageSplitter] (verified: per-stage char offsets). Returns
     * null when the text isn't splittable or the offset lands before the first stage.
     */
    fun stagePrefixAt(chunkText: String, relOffset: Int): StagePrefix? = runCatching {
        val stages = dev.brikk.house.sql.parser.PipeStageSplitter.split(chunkText, "doris").stages
        if (stages.isEmpty()) return null
        val index = stages.indexOfLast { relOffset >= it.start }
        if (index < 0) return null
        val end = (stages[index].endInclusive + 1).coerceAtMost(chunkText.length)
        StagePrefix(chunkText.substring(0, end), index + 1, stages.size)
    }.getOrNull()

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

    /**
     * EXACT map-back via the engine SourceMap ([TranspileResult.mapErrorToSource], strict mode:
     * null when no positioned node covers the offset). Doris reports ANTLR 0-based `pos`; the
     * engine contract is 1-based col — the +1 is OURS, permanently (upstream doc'd).
     */
    fun mapServerErrorExact(message: String, result: TranspileResult): MappedError? {
        val match = SERVER_POSITION.find(message) ?: return null
        val line = match.groupValues[1].toInt()
        val pos = match.groupValues[2].toInt()
        val sp = runCatching { result.mapErrorToSource(line, pos + 1) }.getOrNull() ?: return null
        val origLine = if (sp.lineStart > 0) sp.lineStart else sp.line
        return MappedError(
            token = null,
            originalLine = origLine,
            transpiledLine = line,
            transpiledPos = pos,
            startOffset = sp.start.takeIf { it >= 0 },
            endOffset = sp.end.takeIf { it >= sp.start },
        )
    }

    // ---------------------------------------------------------------------------------------
    // Per-stage scopes (engine stageShapes, 1:1 with PipeStageSplitter incl. FROM = element 0)
    // ---------------------------------------------------------------------------------------

    private val shapeCache =
        java.util.Collections.synchronizedMap(object : LinkedHashMap<Int, List<Shape>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Shape>>) = size > 32
        })

    /**
     * Column names in scope for completion at chunk-relative [relOffset]: element `k-1` of
     * [SqlFragment.stageShapes] where `k` is the caret's 0-based splitter stage — the base
     * relation (das columns of the FROM table, when [baseTable]/[baseColumns] are known) for the
     * FROM/WHERE-adjacent start. `[*]` scopes degrade to [baseColumns]. Cached per
     * (chunk text, base) — stageShapes is O(stages) qualify passes (engine guidance: never
     * per-keystroke). Null on any engine failure (caller falls back to its heuristics).
     */
    fun stageScopeAt(chunkText: String, relOffset: Int, baseTable: String?, baseColumns: List<String>?): List<String>? =
        runCatching {
            val prefix = stagePrefixAt(chunkText, relOffset) ?: return null
            val k = prefix.stage - 1 // stagePrefixAt is 1-based; contract indices are 0-based
            if (k <= 0) return baseColumns // inside FROM: only the base relation exists
            val key = 31 * chunkText.hashCode() + (baseColumns?.hashCode() ?: 0)
            val shapes = shapeCache.getOrPut(key) {
                val catalog = if (baseTable != null && !baseColumns.isNullOrEmpty()) {
                    val shape = Shape(baseColumns.map { ColumnShape(it, "UNKNOWN", null) })
                    val names = buildMap {
                        put(baseTable, shape)
                        put(baseTable.substringAfterLast('.'), shape)
                    }
                    ShapeCatalog(names, emptyMap())
                } else {
                    ShapeCatalog(emptyMap(), emptyMap())
                }
                SqlFragment(chunkText.trim().removeSuffix(";"), "doris").stageShapes(catalog)
            }
            val scope = shapes.getOrNull(k - 1)?.names() ?: return baseColumns
            // Drop engine post-processing names (_col_N): they exist only in the DESUGARED SQL —
            // a user cannot type them in pipe syntax (alias the expression to name it instead).
            val typeable = scope.filter { it != "*" && !it.matches(Regex("_col_\\d+")) }
            if (typeable.isEmpty()) baseColumns else typeable
        }.getOrNull()

    private val SERVER_POSITION = Regex("""\(line (\d+), pos (\d+)\)""")
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
