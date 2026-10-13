package dev.sort.doris.sql

import dev.brikk.house.intellij.ColumnNullability
import dev.brikk.house.intellij.ShapeResult
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.Node
import dev.brikk.house.sql.optimizer.lineageAll
import dev.brikk.house.sql.parser.PipeStageSplitter
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment

/** Core lineage proves passthrough identity. Computed expressions never inherit a Kotlin type. */
internal object BrikkPipeColumnDisplay {
    private data class Key(val sql: String, val inputs: Map<String, List<BrikkColumnDisplay>>)
    private val cache = object : LinkedHashMap<Key, Map<String, BrikkColumnDisplay>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Map<String, BrikkColumnDisplay>>) = size > 32
    }

    fun at(text: String, offset: Int, contracts: Map<String, ShapeResult>): Map<String, BrikkColumnDisplay>? {
        val stages = PipeStageSplitter.split(text, "doris").stages
        val stage = stages.indexOfLast { offset >= it.start }
        if (stage <= 0) return null
        val prefix = text.substring(0, stages[stage - 1].endInclusive + 1).trim().removeSuffix(";")
        val fragment = SqlFragment(prefix, "doris")
        // Mixed physical/Kotlin inference is not a presentation fact we can assert.
        if (fragment.sourceTables.isNotEmpty()) return null
        val inputs = fragment.tableSlots.mapNotNull { slot ->
            val shape = (contracts[slot] as? ShapeResult.Available)?.shape ?: return@mapNotNull null
            slot to shape.columns.map { BrikkColumnDisplay.from(it, slot) }
        }.toMap()
        val key = Key(prefix, inputs)
        synchronized(cache) { cache[key] }?.let { return it }
        val catalog = ShapeCatalog(emptyMap(), inputs.mapValues { (_, columns) -> Shape(columns.map {
            ColumnShape(it.name, "UNKNOWN", when (it.nullability) {
                ColumnNullability.NON_NULL -> false
                ColumnNullability.NULLABLE -> true
                ColumnNullability.UNKNOWN -> null
            })
        }) })
        val shape = fragment.outputShape(catalog)
        val lowered = fragment.toStandardSql("doris", inputs = catalog, expandStars = true)
        val schema = catalog.slots.mapValues { it.value.toSchemaMapping() }
        val lineage = lineageAll(lowered, schema = schema, dialect = Dialects.forName("doris"), trimSelects = false)
        val inherited = shape.columns.mapNotNull { column ->
            val node = lineage[column.name] ?: return@mapNotNull null
            val leaf = passthrough(node) ?: return@mapNotNull null
            val table = leaf.expression as? Table ?: return@mapNotNull null
            val reference = (SqlFragment("SELECT ${leaf.name}", "doris").ast as? Select)
                ?.expressionsArg?.singleOrNull() as? Column ?: return@mapNotNull null
            val contract = inputs[table.name]?.singleOrNull { it.name.equals(reference.name, true) } ?: return@mapNotNull null
            column.name to contract.copy(name = column.name, origin = reference.table.ifBlank { table.name },
                nullability = when (column.nullable) {
                    true -> ColumnNullability.NULLABLE
                    false -> ColumnNullability.NON_NULL
                    null -> ColumnNullability.UNKNOWN
                })
        }.toMap()
        val display = shape.columns.filter { it.name != "*" && !it.name.matches(Regex("_col_\\d+")) }.associate { column ->
            column.name to (inherited[column.name] ?: BrikkColumnDisplay(column.name, null, ColumnNullability.UNKNOWN, null))
        }
        synchronized(cache) { cache[key] = display }
        return display
    }

    private fun passthrough(node: Node): Node? {
        var current = node
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Node, Boolean>())
        while (seen.add(current)) {
            if (current.downstream.isEmpty()) return current.takeIf { it.expression is Table }
            val projections = (current.source as? Select)?.expressionsArg?.filterIsInstance<Expression>().orEmpty()
                .map { it.aliasOrName.lowercase(java.util.Locale.ROOT) }.filter { it.isNotBlank() }
            if (projections.count { it == current.expression.aliasOrName.lowercase(java.util.Locale.ROOT) } > 1) return null
            val expression: Expression = (current.expression as? Alias)?.thisArg as? Expression ?: current.expression
            if (expression !is Column || current.downstream.size != 1) return null
            current = current.downstream.single()
        }
        return null
    }
}
