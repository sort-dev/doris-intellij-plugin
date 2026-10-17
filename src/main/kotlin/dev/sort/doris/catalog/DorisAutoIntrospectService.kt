package dev.sort.doris.catalog

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectService
import dev.brikk.house.sql.intellij.catalog.IntrospectBannerProvider
import dev.brikk.house.sql.intellij.catalog.IntrospectNamespaceIntention
import dev.brikk.house.sql.intellij.catalog.OutOfScopeReferenceFilter
import dev.brikk.house.sql.intellij.catalog.QualifiedPathIntrospectContributor
import dev.sort.doris.DorisCatalogs
import kotlinx.coroutines.CoroutineScope

class DorisAutoIntrospectService(project: Project, scope: CoroutineScope) : AutoIntrospectService(project, scope) {
    override val profile = DorisCatalogProfile

    /**
     * Doris lists its catalogs and a catalog's databases itself ([DorisNamespaceListing]): cheap name listings
     * on a helper connection, which also bring a cleared data source back. A catalog of a model the platform
     * introspected, and a database's tables, are the platform's refresh.
     */
    override suspend fun listNamespaces(dataSource: LocalDataSource, parent: List<String>, requested: List<String>): Boolean? {
        if (!DorisCatalogs.enabled) return super.listNamespaces(dataSource, parent, requested)
        val listing = project.service<DorisNamespaceListing>()
        return when (parent.size) {
            0 -> listing.listRoots(dataSource, requested)
            1 -> if (listing.listsCatalogsOf(dataSource)) listing.listDatabasesOf(dataSource, parent.single()) else null
            else -> null
        }
    }
}

class DorisIntrospectBannerProvider : IntrospectBannerProvider(DorisAutoIntrospectService::class.java)

class DorisQualifiedPathIntrospectContributor : QualifiedPathIntrospectContributor(DorisAutoIntrospectService::class.java)

class DorisOutOfScopeReferenceFilter : OutOfScopeReferenceFilter(DorisAutoIntrospectService::class.java)

class DorisIntrospectNamespaceIntention : IntrospectNamespaceIntention(DorisAutoIntrospectService::class.java, "Doris")
