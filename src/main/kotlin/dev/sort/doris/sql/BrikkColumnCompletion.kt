package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.database.model.ObjectKind
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlAsExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlResolveResult
import com.intellij.sql.symbols.virtual.DasVirtualSymbol
import dev.brikk.house.intellij.ColumnContract
import dev.brikk.house.intellij.ColumnNullability
import dev.brikk.house.intellij.NavigationKind
import dev.brikk.house.intellij.ShapeResult
import dev.brikk.house.sql.intellij.SqlColumnCompletion
import dev.brikk.house.sql.intellij.SqlColumnFacts
import dev.brikk.house.sql.intellij.completion.SqlLookupStyle
import dev.sort.doris.brikk.BrikkDorisContext

/** Display metadata only. SQL types, resolution, insertion and navigation stay with the platform. */
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

    fun lookup(): LookupElement = SqlColumnCompletion.create(facts(), SqlLookupStyle(icon = AllIcons.Nodes.Field, priority = 120.0))

    companion object {
        fun from(column: ColumnContract, origin: String) = BrikkColumnDisplay(column.name,
            column.kotlinClassId, column.nullability, origin, column.navigation?.kind == NavigationKind.RELATION_FALLBACK)
    }
}

class BrikkColumnCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!BrikkDorisContext.isActive(file)) return
        val contracts = BrikkRelationColumns.contracts(file)
        val qualifier = PsiTreeUtil.getParentOfType(parameters.position, SqlReferenceExpression::class.java, false)
            ?.qualifierExpression?.text
        // Use the platform's repaired completion PSI, not the original dangling `e.` query.
        val query = PsiTreeUtil.getParentOfType(parameters.position, SqlQueryExpression::class.java, false)
            ?: parameters.position.containingFile
        val table = (query as? SqlQueryExpression)?.tableExpression ?: return
        val columns = PsiTreeUtil.findChildrenOfType(table, SqlFunctionCallExpression::class.java).flatMap { call ->
            if (PsiTreeUtil.getParentOfType(call, SqlQueryExpression::class.java) !== query) return@flatMap emptyList()
            val slot = BrikkRelationColumns.slotName(call) ?: return@flatMap emptyList()
            val shape = (contracts[slot] as? ShapeResult.Available)?.shape ?: return@flatMap emptyList()
            val alias = (call.parent?.parent as? SqlAsExpression)?.name ?: slot
            if (qualifier != null && !qualifier.equals(alias, true)) return@flatMap emptyList()
            shape.columns.map { BrikkColumnDisplay.from(it, alias).facts(call.textRange) }
        }
        SqlColumnCompletion.createOrDecorate(parameters, result, columns,
            SqlLookupStyle(icon = AllIcons.Nodes.Field, priority = 120.0)) { lookup ->
            displayFor(lookup, query, contracts, qualifier)
        }
    }

    private fun displayFor(lookup: LookupElement, query: PsiElement, contracts: Map<String, ShapeResult>, qualifier: String?): SqlColumnFacts? {
        val symbol = ((lookup.`object` as? SqlResolveResult)?.targetSymbol as? DasVirtualSymbol)
            ?: lookup.`object` as? DasVirtualSymbol ?: lookup.psiElement as? DasVirtualSymbol ?: return null
        if (symbol.kind != ObjectKind.COLUMN) return null
        val source = symbol.contextElement ?: return null
        val name = symbol.name
        // 262+ gives completion symbols the slot call itself as context: an exact relation binding.
        (source as? SqlFunctionCallExpression)?.let { call ->
            val slot = BrikkRelationColumns.slotName(call) ?: return null
            val shape = (contracts[slot] as? ShapeResult.Available)?.shape ?: return null
            val column = shape.columns.singleOrNull { it.name.equals(name, true) } ?: return null
            return BrikkColumnDisplay.from(column, (call.parent?.parent as? SqlAsExpression)?.name ?: slot).facts(call.textRange)
        }
        val url = source.containingFile?.virtualFile?.url
        fun at(location: dev.brikk.house.intellij.SourceLocation?) = location != null && location.fileUrl == url &&
            location.start == source.textRange.startOffset && location.end == source.textRange.endOffset
        val matches = PsiTreeUtil.findChildrenOfType(query, SqlFunctionCallExpression::class.java).flatMap { call ->
            val slot = BrikkRelationColumns.slotName(call) ?: return@flatMap emptyList()
            val shape = (contracts[slot] as? ShapeResult.Available)?.shape ?: return@flatMap emptyList()
            val origin = (call.parent?.parent as? SqlAsExpression)?.name ?: slot
            shape.columns.filter { column ->
                // Exact property location, else the relation/host fallback chosen for generated columns.
                // Exact property, the relation fallback, or this fragment's own Kotlin host.
                column.name.equals(name, true) && (at(column.navigation?.location) ||
                    at(shape.navigation?.location) || source is com.intellij.psi.PsiLanguageInjectionHost)
            }.map { BrikkColumnDisplay.from(it, origin).facts(call.textRange) }
        }
        val qualified = qualifier?.let { q -> matches.filter { it.relationAlias.equals(q, true) } }.orEmpty()
        return (qualified.ifEmpty { matches }).distinct().singleOrNull()
    }
}
