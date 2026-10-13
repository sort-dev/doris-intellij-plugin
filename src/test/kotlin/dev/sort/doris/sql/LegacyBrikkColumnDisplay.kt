package dev.sort.doris.sql

import com.intellij.codeInsight.lookup.LookupElement
import dev.brikk.house.intellij.ColumnContract
import dev.brikk.house.intellij.ColumnNullability
import dev.brikk.house.intellij.NavigationKind
import dev.brikk.house.sql.intellij.SqlColumnCompletion
import dev.brikk.house.sql.intellij.SqlColumnFacts

/** Legacy corpus observation until the shared-analyzer corpus packet. */
internal data class BrikkColumnDisplay(
    val name: String,
    val kotlinClassId: String?,
    val nullability: ColumnNullability,
    val origin: String?,
    val relationFallback: Boolean = false,
) {
    fun facts(source: Any? = origin) = SqlColumnFacts(name, kotlinClassId?.substringAfterLast('.'),
        when (nullability) {
            ColumnNullability.NON_NULL -> false
            ColumnNullability.NULLABLE -> true
            ColumnNullability.UNKNOWN -> null
        }, origin, when {
            relationFallback -> "relation contract"
            origin != null -> "brikk column"
            else -> "PIPE column"
        }, source)

    fun lookup(): LookupElement = SqlColumnCompletion.create(facts())

    companion object {
        fun from(column: ColumnContract, origin: String) = BrikkColumnDisplay(column.name,
            column.kotlinClassId, column.nullability, origin, column.navigation?.kind == NavigationKind.RELATION_FALLBACK)
    }
}
