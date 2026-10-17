package dev.sort.doris.sql

import com.intellij.openapi.extensions.ExtensionPointName
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
class DorisRelationInspectionSuppressor : ExternalRelationInspectionSuppressor(DorisRelationScope)
class DorisRelationNavigation : ExternalRelationGotoDeclarationHandler(DorisRelationScope)
class DorisRelationAnnotator : ExternalRelationAnnotator(DorisRelationScope)
