package dev.sort.doris.plan

import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.CatalogSchemaSearchPath
import dev.brikk.house.sql.intellij.catalog.NamespaceProbe
import dev.brikk.house.sql.intellij.catalog.TextExplainConfiguration
import dev.brikk.house.sql.intellij.catalog.TextExplainProfile
import dev.brikk.house.sql.intellij.catalog.TextExplainService
import dev.brikk.house.sql.shape.ExplainableStatements
import dev.sort.doris.DorisDbms
import dev.sort.doris.pipes.DorisPipeDialect
import kotlinx.coroutines.CoroutineScope

object DorisTextExplainProfile : TextExplainProfile {
    override val dbms = DorisDbms.DORIS
    override val productLabel = "Doris"
    override val dialect = "doris"
    override val enabled = true
    override val pipe = DorisPipeDialect
    override val explainableStatements = ExplainableStatements.Queries
    override val searchPath = CatalogSchemaSearchPath('`', "use {catalog}.{schema}", "use {schema}", "SWITCH {catalog}",
        catalogProbes = listOf(NamespaceProbe.Value("select current_catalog()"),
            NamespaceProbe.FlaggedRow("SHOW CATALOGS", "CatalogName", "IsCurrent")),
        schemaProbes = listOf(NamespaceProbe.Value("select database()")), defaultCatalog = "internal")
}

open class DorisTextExplainService(project: Project, scope: CoroutineScope) : TextExplainService(project, scope) {
    override val profile = DorisTextExplainProfile
}

class DorisTextExplainConfiguration : TextExplainConfiguration(DorisTextExplainService::class.java)
