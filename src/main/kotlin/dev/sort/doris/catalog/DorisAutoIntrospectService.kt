package dev.sort.doris.catalog

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.database.psi.DbElement
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectService
import dev.sort.doris.DorisCatalogs
import dev.brikk.house.sql.intellij.catalog.IntrospectBannerProvider
import dev.brikk.house.sql.intellij.catalog.IntrospectNamespaceIntention
import dev.brikk.house.sql.intellij.catalog.OutOfScopeReferenceFilter
import dev.brikk.house.sql.intellij.catalog.QualifiedPathIntrospectContributor
import kotlinx.coroutines.CoroutineScope

class DorisAutoIntrospectService(project: Project, scope: CoroutineScope) : AutoIntrospectService(project, scope) {
    override val profile = DorisCatalogProfile

    /** Doris lists its catalogs itself ([DorisNamespaceListing.listRoots]), which also brings a cleared data source back. */
    override suspend fun listNamespaces(dataSource: LocalDataSource): Boolean =
        if (DorisCatalogs.enabled) project.service<DorisNamespaceListing>().listRoots(dataSource) else super.listNamespaces(dataSource)
}

class DorisIntrospectBannerProvider : IntrospectBannerProvider(DorisAutoIntrospectService::class.java)

class DorisQualifiedPathIntrospectContributor : QualifiedPathIntrospectContributor(DorisAutoIntrospectService::class.java) {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!qualifiesAStatementName(parameters.position)) super.fillCompletionVariants(parameters, result)
    }
}

/**
 * Whether the path before [position] starts with a name the statement itself defines (a table alias, a
 * CTE, a derived table): `o.` after `FROM orders AS o` names no namespace, so nothing is listed or loaded
 * for it. A path starting with a table still loads that table's columns.
 */
internal fun qualifiesAStatementName(position: PsiElement): Boolean {
    val reference = PsiTreeUtil.getParentOfType(position, SqlReferenceExpression::class.java, false) ?: return false
    val first = generateSequence(reference.qualifierExpression as? SqlReferenceExpression) { it.qualifierExpression as? SqlReferenceExpression }
        .lastOrNull() ?: return false
    val target = first.reference?.resolve() ?: return false
    return target !is DbElement
}

class DorisOutOfScopeReferenceFilter : OutOfScopeReferenceFilter(DorisAutoIntrospectService::class.java)

class DorisIntrospectNamespaceIntention : IntrospectNamespaceIntention(DorisAutoIntrospectService::class.java, "Doris")
