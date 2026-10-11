package dev.sort.doris.pipes

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.PipeSplitResult
import dev.brikk.house.sql.parser.PipeStageSplitter
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.TranspileResult
import dev.sort.doris.sql.DorisSyntaxError
import dev.sort.doris.sql.DORIS_PIPE_DIAGNOSTIC_PREFIX
import com.intellij.openapi.progress.ProgressManager
import org.apache.doris.nereids.exceptions.ParseException as DorisParseException
import org.apache.doris.sqlparser.DorisSqlParser

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
        data class Ok(
            val dorisSql: String,
            val result: TranspileResult? = null,
            val validationError: Err? = null,
        ) : Transpile {
            val unsupportedMessages: List<String> get() = result?.unsupportedMessages.orEmpty()

            val executionError: Err? get() = validationError ?: unsupportedMessages.takeIf { it.isNotEmpty() }?.let {
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
    fun transpile(text: String): Transpile = transpile(text, allowNamedParameters = true)

    internal fun transpile(
        text: String,
        allowNamedParameters: Boolean,
        allowFirstFromStage: Boolean = false,
    ): Transpile {
        val chunks = DorisPipes.chunks(text)
        if (chunks.none { it.hasPipeOperator } && !allowFirstFromStage) return Transpile.NotPipe
        chunks.firstOrNull { it.boundaryError != null }?.let {
            return Transpile.Err(null, null, it.boundaryError!!)
        }
        if (chunks.count { it.hasSql } != 1) {
            return Transpile.Err(null, null, "Select exactly one PIPE statement; multiple or mixed statements cannot be executed together.")
        }
        return try {
            val fragment = SqlFragment(text.trim().removeSuffix(";"), "doris")
            if (fragment.ast.findAll<PipeQuery>().none() && !(allowFirstFromStage && fragment.hasFromFirstQuery)) {
                Transpile.NotPipe
            } else {
                val result = fragment.toExecutable("doris", pretty = true)
                ProgressManager.checkCanceled()
                outputRefusal(result) ?: run {
                    val validationError = if (result.unsupportedMessages.isEmpty()) {
                        validateGeneratedSql(result.sql, allowNamedParameters)
                    } else {
                        null
                    }
                    Transpile.Ok(result.sql, result, validationError)
                }
            }
        } catch (e: ParseError) {
            val first = e.errors.firstOrNull()
            Transpile.Err(first?.line, first?.col, first?.description ?: (e.message ?: "pipe parse error"))
        } catch (e: UnsupportedError) {
            // An intentional lowering refusal is handled, never permission to execute raw PIPE SQL.
            Transpile.Err(null, null, e.message ?: "Unsupported PIPE translation")
        } catch (e: TokenError) {
            Transpile.Err(null, null, e.message ?: "Invalid PIPE token")
        } catch (e: ShapeError) {
            Transpile.Err(null, null, e.message ?: "Invalid PIPE statement shape")
        }
    }

    internal fun outputRefusal(result: TranspileResult): Transpile.Err? = when {
        result.isRawPassthroughStatement -> Transpile.Err(null, null, "PIPE translation returned raw SQL.")
        DorisPipes.containsPipeOperator(result.sql) -> Transpile.Err(null, null, "Translation left unlowered PIPE syntax.")
        else -> null
    }

    private fun validateGeneratedSql(sql: String, allowNamedParameters: Boolean): Transpile.Err? {
        ProgressManager.checkCanceled()
        val parserInput = if (allowNamedParameters) maskNamedParameters(sql) else sql
        return try {
            NATIVE_PARSER.parseStatement(parserInput)
            ProgressManager.checkCanceled()
            null
        } catch (failure: DorisParseException) {
            Transpile.Err(
                null,
                null,
                "PIPE execution blocked: generated SQL is not accepted by the embedded Doris parser.\n\n" +
                    (failure.message ?: "Generated Doris SQL failed validation."),
            )
        }
    }

    /** Replace native `:name` tokens with scalar zeroes for the pre-substitution parser pass. */
    private fun maskNamedParameters(sql: String): String {
        val ranges = DorisPipes.namedParameterRanges(sql)
        if (ranges.isEmpty()) return sql
        return buildString(sql.length) {
            append(sql)
            for (range in ranges) {
                setCharAt(range.first, '0')
                for (index in range.first + 1..range.last) setCharAt(index, ' ')
            }
        }
    }

    private val NATIVE_PARSER = DorisSqlParser()

    // ---------------------------------------------------------------------------------------
    // Stage boundaries (execute-to-stage-N; IDEAS §3 "Execute up to stage N")
    // ---------------------------------------------------------------------------------------

    /** Where run-to-stage may cut a pipe program. */
    sealed interface StageCut {
        data object InsideNestedPipeline : StageCut
        data object Unavailable : StageCut
    }

    /** The pipe-program prefix ending at the stage containing [relOffset], plus (1-based stage, total). */
    data class StagePrefix(val text: String, val stage: Int, val totalStages: Int) : StageCut

    /**
     * Cut [chunkText] (one pipe program) down to the stages up to and including the one containing
     * [relOffset] (an offset RELATIVE to [chunkText]). A prefix of pipe stages is itself a valid
     * pipe program, so the result transpiles/executes like any other. Uses the engine's own
     * [dev.brikk.house.sql.parser.PipeStageSplitter] (verified: per-stage char offsets). Returns
     * null when the text isn't splittable or the offset lands before the first stage.
     */
    fun stagePrefixAt(chunkText: String, relOffset: Int): StagePrefix? = runPipeCatching {
        PipeStageSplitter.split(chunkText, "doris").prefixAt(chunkText, relOffset)
    }.getOrNull()

    /** The run-to-stage cut: a top-level stage of a program with at least two stages. */
    fun runnableStageCutAt(chunkText: String, relOffset: Int): StageCut = runPipeCatching {
        val split = PipeStageSplitter.split(chunkText, "doris")
        when {
            split.isInsideNestedPipeline(relOffset) -> StageCut.InsideNestedPipeline
            split.stages.size < 2 -> StageCut.Unavailable
            else -> split.prefixAt(chunkText, relOffset) ?: StageCut.Unavailable
        }
    }.getOrDefault(StageCut.Unavailable)

    private fun PipeSplitResult.prefixAt(chunkText: String, relOffset: Int): StagePrefix? {
        val index = stages.indexOfLast { relOffset >= it.start }
        if (index < 0) return null
        val end = (stages[index].endInclusive + 1).coerceAtMost(chunkText.length)
        return StagePrefix(chunkText.substring(0, end), index + 1, stages.size)
    }

    private data class Frame(val start: Int, val hasPipe: Boolean = false)

    private fun PipeSplitResult.isInsideNestedPipeline(relOffset: Int): Boolean {
        val frames = ArrayDeque<Frame>()
        for (token in tokens) when (token.tokenType) {
            TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> frames.addLast(Frame(token.start))
            TokenType.PIPE_GT -> frames.removeLastOrNull()?.let { frames.addLast(it.copy(hasPipe = true)) }
            TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> {
                val frame = frames.removeLastOrNull()
                if (frame != null && frame.hasPipe && relOffset in frame.start..token.end) return true
            }
            else -> Unit
        }
        return false
    }

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

    private data class ShapeKey(val sql: String, val baseTable: String?, val baseColumns: List<String>?, val slots: Map<String, Shape> = emptyMap())
    private val shapeCacheLock = Any()
    private val shapeCache = object : LinkedHashMap<ShapeKey, List<Shape>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ShapeKey, List<Shape>>) = size > 32
    }

    /**
     * Column names in scope for completion at chunk-relative [relOffset]: element `k-1` of
     * [SqlFragment.stageShapes] where `k` is the caret's 0-based splitter stage — the base
     * relation (das columns of the FROM table, when [baseTable]/[baseColumns] are known) for the
     * FROM/WHERE-adjacent start. `[*]` scopes degrade to [baseColumns]. Cached per
     * (chunk text, base) — stageShapes is O(stages) qualify passes (engine guidance: never
     * per-keystroke). Null on any engine failure (caller falls back to its heuristics).
     */
    fun stageScopeAt(chunkText: String, relOffset: Int, baseTable: String?, baseColumns: List<String>?): List<String>? =
        stageScopeAt(chunkText, relOffset, baseTable, baseColumns, emptyMap())

    /** Kotlin slot contracts feed the existing engine, without a second SQL type system. */
    fun stageScopeAt(chunkText: String, relOffset: Int, baseTable: String?, baseColumns: List<String>?, slots: Map<String, Shape>): List<String>? =
        runPipeCatching {
            val prefix = stagePrefixAt(chunkText, relOffset) ?: return null
            val inputShapeIndex = prefix.stage - 2
            if (inputShapeIndex < 0) return baseColumns // inside FROM: only the base relation exists
            val normalizedSql = chunkText.trim().removeSuffix(";")
            val key = ShapeKey(normalizedSql, baseTable, baseColumns?.toList(), slots.toMap())
            val cached = synchronized(shapeCacheLock) { shapeCache[key] }
            val shapes = cached ?: run {
                val catalog = if (baseTable != null && !baseColumns.isNullOrEmpty()) {
                    val shape = Shape(baseColumns.map { ColumnShape(it, "UNKNOWN", null) })
                    val names = buildMap {
                        put(baseTable, shape)
                        put(baseTable.substringAfterLast('.'), shape)
                    }
                    ShapeCatalog(names, slots)
                } else {
                    ShapeCatalog(emptyMap(), slots)
                }
                val computed = SqlFragment(normalizedSql, "doris").stageShapes(catalog)
                synchronized(shapeCacheLock) { shapeCache[key] ?: computed.also { shapeCache[key] = it } }
            }
            val scope = shapes.getOrNull(inputShapeIndex)?.names() ?: return null
            // Drop engine post-processing names (_col_N): they exist only in the DESUGARED SQL —
            // a user cannot type them in pipe syntax (alias the expression to name it instead).
            val typeable = scope.filter { it != "*" && !it.matches(Regex("_col_\\d+")) }
            if ("*" in scope) (baseColumns.orEmpty() + typeable).distinct().takeIf { it.isNotEmpty() }
            else typeable
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
                col = ((error.col ?: 1) - 1).coerceAtLeast(0),
                length = 2,
                message = "$DORIS_PIPE_DIAGNOSTIC_PREFIX ${error.message}",
            ))
        }
        return out
    }
}
