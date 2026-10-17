package dev.sort.doris.pipes

import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.lower
import dev.brikk.house.sql.parser.PipeStageSplitter
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import dev.sort.doris.sql.DORIS_PIPE_DIAGNOSTIC_PREFIX
import dev.sort.doris.sql.DorisSyntaxError

/**
 * Adapter for the bundled brikk-sql engine. IDE entry points consult the project setting;
 * these SQL/schema operations remain independent of project state and UI lifecycle.
 */
object DorisPipesEngine {

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
            val stages = PipeStageSplitter.split(chunkText, "doris").stages
            val stage = stages.indexOfLast { relOffset >= it.start }
            if (stage < 0) return null
            val inputShapeIndex = stage - 1
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
            val result = runPipeCatching { DorisPipeDialect.lower(chunk.text) }.getOrElse {
                DorisPipes.warn("PIPE diagnostics failed", it)
                PipeTranslation.Refused(null, "PIPE translation failed: ${it.message ?: it.javaClass.simpleName}")
            }
            val message = when (result) {
                is PipeTranslation.Refused -> result.message
                is PipeTranslation.Lowered -> result.blocker
                PipeTranslation.NotPipe -> null
            } ?: continue
            val firstContent = chunk.text.length - chunk.text.trimStart().length
            val offset = ((result as? PipeTranslation.Refused)?.range?.start ?: firstContent).coerceAtLeast(firstContent)
            val before = chunk.text.take(offset)
            out.add(DorisSyntaxError(
                line = (chunk.startLine + before.count { it == '\n' } - chunk.text.take(firstContent).count { it == '\n' })
                    .coerceIn(chunk.startLine, chunk.endLine),
                col = before.length - (before.lastIndexOf('\n') + 1),
                length = 2,
                message = "$DORIS_PIPE_DIAGNOSTIC_PREFIX $message",
            ))
        }
        return out
    }
}
