package dev.sort.doris.sql

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.inspections.suppression.SqlInspectionSuppressorDelegate
import com.intellij.sql.psi.SqlCreateTableStatement
import com.intellij.sql.psi.SqlQueryExpression
import dev.brikk.house.sql.intellij.catalog.DasRelationFacts
import dev.brikk.house.sql.intellij.relation.*
import dev.sort.doris.brikk.DorisHostSqlChannel

object DorisRelationScope : RelationScopeProfile {
    private val external = ExtensionPointName.create<ExternalRelationFacts>("dev.sort.doris-intellij-plugin.relationFacts")
    private val physical = DasRelationFacts(dorisDataSourceResolver)
    private val tableFunctions = TableFunctionFacts(DorisCompletionProfile::snapshot)
    override val language get() = DorisSqlDialect.INSTANCE
    override val dialect = "doris"
    override val providers get() = external.extensionList + physical + tableFunctions
    override val hostSql get() = DorisHostSqlChannel
}

class DorisRelationCompletion : ExternalRelationCompletionContributor(DorisRelationScope)
class DorisRelationResolve : ExternalRelationResolveExtension(DorisRelationScope)
/**
 * The shared suppressor, except in a CREATE TABLE's own clauses (keys, partitioning, indexes): they name
 * the columns the statement declares, which the platform resolves, so its misses there stand.
 */
class DorisRelationInspectionSuppressor : SqlInspectionSuppressorDelegate {
    private val shared = object : ExternalRelationInspectionSuppressor(DorisRelationScope) {}

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean =
        shared.isSuppressedFor(element, toolId) && !inOwnTableClause(element)

    private fun inOwnTableClause(element: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(element, SqlCreateTableStatement::class.java, false) != null &&
            PsiTreeUtil.getParentOfType(element, SqlQueryExpression::class.java, true, SqlCreateTableStatement::class.java) == null
}
class DorisRelationNavigation : ExternalRelationGotoDeclarationHandler(DorisRelationScope)
class DorisRelationAnnotator : ExternalRelationAnnotator(DorisRelationScope)
