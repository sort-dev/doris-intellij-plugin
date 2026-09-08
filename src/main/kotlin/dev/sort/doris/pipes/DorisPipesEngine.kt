package dev.sort.doris.pipes

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.TranspileResult
import dev.sort.doris.sql.DorisSyntaxError
import com.intellij.openapi.progress.ProgressManager

/**
 * Adapter for the bundled brikk-sql engine. IDE entry points consult the project setting;
 * these SQL/schema operations remain independent of project state and UI lifecycle.
 */
object DorisPipesEngine {

    // ---------------------------------------------------------------------------------------
    // Transpile (the execute-time payload)
    // ---------------------------------------------------------------------------------------

    sealed interface Transpile {
        /** Generated SQL, not execution approval: [executionError] may block lossy output.
         *  [result] carries the engine SourceMap (identity-tied to [dorisSql]) for exact
         *  server-error map-back; null only in unit-test fabrication. */
        data class Ok(val dorisSql: String, val result: TranspileResult? = null) : Transpile {
            val unsupportedMessages: List<String> get() = result?.unsupportedMessages.orEmpty()

            val executionError: Err? get() = unsupportedMessages.takeIf { it.isNotEmpty() }?.let {
                Err(null, null, "PIPE execution blocked: translation reports unsupported or lossy behavior.\n\n" + it.joinToString("\n\n"))
            }
        }

        /** Pipe-looking but the engine rejects it; positions are 1-based like fe-sql-parser's. */
        data class Err(val line: Int?, val col: Int?, val message: String) : Transpile

        /** Parses fine but is NOT a pipe program (e.g. `|>` inside a string literal). */
        object NotPipe : Transpile
    }

    /** Automatic statement detection must not execute a prefix of an unterminated construct. */
    fun transpile(chunk: DorisPipes.Chunk): Transpile =
        chunk.boundaryError?.let { Transpile.Err(null, null, it) } ?: transpile(chunk.text)

    /**
     * Parse [text] as Doris (the engine's Doris dialect accepts pipe syntax natively), and if it
     * is a pipe program, produce the executable (desugared) Doris SQL + its SourceMap in one
     * generator pass ([SqlFragment.toExecutable]; identity-guaranteed upstream).
     */
    fun transpile(text: String): Transpile {
        val chunks = DorisPipes.chunks(text)
        if (chunks.none { it.hasPipeOperator }) return Transpile.NotPipe
        chunks.firstOrNull { it.boundaryError != null }?.let {
            return Transpile.Err(null, null, it.boundaryError!!)
        }
        if (chunks.count { it.hasSql } != 1) {
            return Transpile.Err(null, null, "Select exactly one PIPE statement; multiple or mixed statements cannot be executed together.")
        }
        return try {
            val fragment = SqlFragment(text.trim().removeSuffix(";"), "doris")
            if (fragment.ast !is PipeQuery) {
                Transpile.NotPipe
            } else {
                val result = fragment.toExecutable("doris", pretty = true)
                ProgressManager.checkCanceled()
                Transpile.Ok(result.sql, result)
            }
        } catch (e: ParseError) {
            val first = e.errors.firstOrNull()
            Transpile.Err(first?.line, first?.col, first?.description ?: (e.message ?: "pipe parse error"))
        } catch (e: UnsupportedError) {
            // An intentional lowering refusal is handled, never permission to execute raw PIPE SQL.
            Transpile.Err(null, null, e.message ?: "Unsupported PIPE translation")
        }
    }

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
    fun stagePrefixAt(chunkText: String, relOffset: Int): StagePrefix? = runPipeCatching {
        val stages = dev.brikk.house.sql.parser.PipeStageSplitter.split(chunkText, "doris").stages
        if (stages.isEmpty()) return null
        val index = stages.indexOfLast { relOffset >= it.start }
        if (index < 0) return null
        val end = (stages[index].endInclusive + 1).coerceAtMost(chunkText.length)
        StagePrefix(chunkText.substring(0, end), index + 1, stages.size)
    }.getOrNull()

    // ---------------------------------------------------------------------------------------
    // Server-error map-back (exact, via the engine SourceMap)
    // ---------------------------------------------------------------------------------------

    /**
     * EXACT map-back via the engine SourceMap ([TranspileResult.mapErrorToSource], strict mode:
     * null when no positioned node covers the offset). Doris reports a 0-based code-point
     * `pos`; the engine expects a 1-based UTF-16 column. Normalize on the generated line.
     */
    fun mapServerErrorExact(message: String, result: TranspileResult): DorisPipes.MappedError? {
        val match = DorisPipes.SERVER_POSITION.find(message) ?: return null
        val line = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val pos = match.groupValues[2].toIntOrNull() ?: return null
        val outputLine = result.sql.splitToSequence('\n').elementAtOrNull(line - 1) ?: return null
        val sp = runPipeCatching {
            val utf16Pos = outputLine.offsetByCodePoints(0, pos)
            result.mapErrorToSource(line, utf16Pos + 1)
        }.getOrNull() ?: return null
        val origLine = if (sp.lineStart > 0) sp.lineStart else sp.line
        return DorisPipes.MappedError(
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
        runPipeCatching {
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

    // ---------------------------------------------------------------------------------------
    // Engine-verdict annotations (per pipe chunk)
    // ---------------------------------------------------------------------------------------

    /**
     * The engine's own syntax verdict for every pipe chunk of [text], as [DorisSyntaxError]s with
     * ABSOLUTE (whole-document) 1-based lines — drop-in replacements for the fe-sql-parser errors
     * the annotator suppresses on those chunks. Execution-blocking warnings are visible too.
     * Unexpected failures stay local to the statement; cancellation always propagates.
     */
    fun pipeSyntaxErrors(text: String): List<DorisSyntaxError> {
        if (!text.contains(DorisPipes.MARKER)) return emptyList()
        val out = ArrayList<DorisSyntaxError>()
        for (chunk in DorisPipes.chunks(text)) {
            if (!chunk.hasPipeOperator) continue
            val result = runPipeCatching { transpile(chunk) }.getOrElse {
                DorisPipes.warn("PIPE diagnostics failed", it)
                Transpile.Err(null, null, "PIPE translation failed: ${it.message ?: it.javaClass.simpleName}")
            }
            val error = when (result) {
                is Transpile.Err -> result
                is Transpile.Ok -> result.executionError
                Transpile.NotPipe -> null
            } ?: continue
            out.add(DorisSyntaxError(
                line = chunk.startLine + (error.line ?: 1) - 1,
                col = (error.col ?: 1).coerceAtLeast(0),
                length = 2,
                message = "Doris Pipes: ${error.message}",
            ))
        }
        return out
    }
}
