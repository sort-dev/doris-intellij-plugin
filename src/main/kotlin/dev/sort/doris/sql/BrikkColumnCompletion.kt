package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.database.model.ObjectKind
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
import dev.sort.doris.brikk.BrikkDorisContext

/** Display metadata only. SQL types, resolution, insertion and navigation stay with the platform. */
internal data class BrikkColumnDisplay(
    val name: String,
    val kotlinClassId: String?,
    val nullability: ColumnNullability,
    val origin: String?,
    val relationFallback: Boolean = false,
) {
    fun lookup(): LookupElement = SqlColumnCompletion.create(name, kotlinClassId?.substringAfterLast('.'),
        when (nullability) {
            ColumnNullability.NON_NULL -> false
            ColumnNullability.NULLABLE -> true
            ColumnNullability.UNKNOWN -> null
        }, origin, when {
            relationFallback -> "relation contract"
            origin != null -> "brikk column"
            else -> "PIPE column"
        })

    fun presentation(): LookupElementPresentation = LookupElementPresentation().also { lookup().renderElement(it) }
    val typeText: String get() = requireNotNull(presentation().typeText)
    val tailText: String get() = presentation().tailText.orEmpty()

    companion object {
        fun from(column: ColumnContract, origin: String) = BrikkColumnDisplay(column.name,
            column.kotlinClassId, column.nullability, origin, column.navigation?.kind == NavigationKind.RELATION_FALLBACK)
    }
}

/** Decorate only native SQL candidates whose source context matches a current helper contract. */
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
        result.runRemainingContributors(parameters) { completion ->
            val display = displayFor(completion.lookupElement, query, contracts, qualifier)
            if (display == null) result.passResult(completion)
            else {
                val shared = display.presentation()
                val decorated = object : LookupElementDecorator<LookupElement>(completion.lookupElement) {
                    override fun renderElement(presentation: LookupElementPresentation) {
                        super.renderElement(presentation)
                        presentation.typeText = shared.typeText
                        presentation.tailText = shared.tailText
                    }
                }
                CompletionResult.wrap(decorated, completion.prefixMatcher, completion.sorter)?.let(result::passResult)
            }
        }
    }

    private fun displayFor(lookup: LookupElement, query: PsiElement, contracts: Map<String, ShapeResult>, qualifier: String?): BrikkColumnDisplay? {
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
            return BrikkColumnDisplay.from(column, (call.parent?.parent as? SqlAsExpression)?.name ?: slot)
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
            }.map { BrikkColumnDisplay.from(it, origin) }
        }
        val qualified = qualifier?.let { q -> matches.filter { it.origin.equals(q, true) } }.orEmpty()
        return (qualified.ifEmpty { matches }).distinct().singleOrNull()
    }
}
