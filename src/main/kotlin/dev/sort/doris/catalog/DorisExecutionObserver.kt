package dev.sort.doris.catalog

import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.ExecutionObserver
import dev.brikk.house.sql.intellij.catalog.ExecutionObserverProfile
import dev.brikk.house.sql.intellij.catalog.ExecutionObserverStartup
import dev.brikk.house.sql.intellij.catalog.StatementHeadDetector
import dev.brikk.house.sql.intellij.catalog.StatementRules
import dev.sort.doris.DorisDbms
import dev.sort.doris.sql.DorisLiveCatalogService
import dev.sort.doris.sql.dorisDataSourceResolver
import kotlinx.coroutines.CoroutineScope

object DorisExecutionObserverProfile : ExecutionObserverProfile {
    override val dbms = DorisDbms.DORIS
    override val dataSources = dorisDataSourceResolver
    override val statements = StatementHeadDetector(StatementRules.DORIS, "doris")
    override val liveCatalog = DorisLiveCatalogService::class.java
    override val autoIntrospect = DorisAutoIntrospectService::class.java
}

class DorisExecutionObserver(project: Project, scope: CoroutineScope) : ExecutionObserver(project, scope) {
    override val profile = DorisExecutionObserverProfile
}

class DorisExecutionObserverStartup : ExecutionObserverStartup(DorisExecutionObserver::class.java)
