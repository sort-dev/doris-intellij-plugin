package dev.sort.doris.sql

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.DasUtil
import com.intellij.psi.PsiElement
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlQueryExpression
import dev.brikk.house.sql.metadata.OutputSchema
import dev.brikk.house.sql.intellij.catalog.DasRelationFacts
import dev.brikk.house.sql.intellij.relation.*
import dev.sort.doris.brikk.DorisHostSqlChannel

object DorisRelationScope : RelationScopeProfile {
    private val external = ExtensionPointName.create<ExternalRelationFacts>("dev.sort.doris-intellij-plugin.relationFacts")
    private val physical = DasRelationFacts(dorisDataSourceResolver)
    override val language get() = DorisSqlDialect.INSTANCE
    override val dialect = "doris"
    override val providers get() = external.extensionList + physical
    override val hostSql get() = DorisHostSqlChannel
}

class DorisRelationCompletion : ExternalRelationCompletionContributor(DorisRelationScope)
class DorisRelationResolve : ExternalRelationResolveExtension(DorisRelationScope)
/**
 * The shared suppressor, except where the platform's resolver knows a column's sources better than the
 * shared analysis: table functions whose output Doris types (`tasks("type"="mv")`), and a query reading
 * only tables the platform resolves with their columns (a data source the analysis does not see, say).
 * The platform's misses there stand.
 */
class DorisRelationInspectionSuppressor : SqlInspectionSuppressorDelegate {
    private val shared = object : ExternalRelationInspectionSuppressor(DorisRelationScope) {}

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean =
        shared.isSuppressedFor(element, toolId) && !readsTypedTableFunction(element) && !readsOnlyKnownTables(element)

    private fun readsOnlyKnownTables(element: PsiElement): Boolean {
        val reference = element as? SqlReferenceExpression ?: PsiTreeUtil.getParentOfType(element, SqlReferenceExpression::class.java) ?: return false
        if (reference.referenceElementType.targetKind != ObjectKind.COLUMN) return false
        val from = PsiTreeUtil.getParentOfType(reference, SqlQueryExpression::class.java)?.tableExpression?.fromClause ?: return false
        if (PsiTreeUtil.findChildOfType(from, SqlQueryExpression::class.java) != null) return false
        val tables = PsiTreeUtil.findChildrenOfType(from, SqlReferenceExpression::class.java)
            .filter { it.referenceElementType.targetKind == ObjectKind.TABLE && it.parent !is SqlReferenceExpression }
        return tables.isNotEmpty() && tables.all { table ->
            (table.resolve() as? DasTable)?.let { DasUtil.getColumns(it).isNotEmpty } == true
        }
    }

    private fun readsTypedTableFunction(element: PsiElement): Boolean {
        var query = PsiTreeUtil.getParentOfType(element, SqlQueryExpression::class.java)
        while (query != null) {
            val from = query.tableExpression
            if (from != null && PsiTreeUtil.findChildrenOfType(from, SqlFunctionCallExpression::class.java).any {
                    DorisTableFunctions.byName(it.nameElement?.name)?.schema.let { schema -> schema != null && schema !is OutputSchema.Open }
                }) return true
            query = PsiTreeUtil.getParentOfType(query, SqlQueryExpression::class.java)
        }
        return false
    }
}
class DorisRelationNavigation : ExternalRelationGotoDeclarationHandler(DorisRelationScope)
class DorisRelationAnnotator : ExternalRelationAnnotator(DorisRelationScope)
