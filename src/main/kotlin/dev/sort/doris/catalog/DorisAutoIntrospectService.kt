package dev.sort.doris.catalog

import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectService
import dev.brikk.house.sql.intellij.catalog.IntrospectBannerProvider
import dev.brikk.house.sql.intellij.catalog.IntrospectNamespaceIntention
import dev.brikk.house.sql.intellij.catalog.OutOfScopeReferenceFilter
import dev.brikk.house.sql.intellij.catalog.QualifiedPathIntrospectContributor
import kotlinx.coroutines.CoroutineScope

class DorisAutoIntrospectService(project: Project, scope: CoroutineScope) : AutoIntrospectService(project, scope) {
    override val profile = DorisCatalogProfile
}

class DorisIntrospectBannerProvider : IntrospectBannerProvider(DorisAutoIntrospectService::class.java)

class DorisQualifiedPathIntrospectContributor : QualifiedPathIntrospectContributor(DorisAutoIntrospectService::class.java)

class DorisOutOfScopeReferenceFilter : OutOfScopeReferenceFilter(DorisAutoIntrospectService::class.java)

class DorisIntrospectNamespaceIntention : IntrospectNamespaceIntention(DorisAutoIntrospectService::class.java, "Doris")
