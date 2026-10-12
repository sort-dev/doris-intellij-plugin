package dev.sort.doris.sql

import dev.brikk.house.sql.intellij.catalog.CatalogRowSource
import dev.brikk.house.sql.intellij.catalog.Harvest
import dev.brikk.house.sql.intellij.catalog.HarvestConnectionInterceptor
import dev.brikk.house.sql.intellij.catalog.HarvestTrigger
import dev.brikk.house.sql.intellij.catalog.LiveCatalogCleanup
import dev.brikk.house.sql.intellij.catalog.LiveCatalogHarvester
import dev.brikk.house.sql.intellij.catalog.LiveCatalogProfile
import dev.brikk.house.sql.intellij.catalog.LiveCatalogService
import dev.brikk.house.sql.intellij.catalog.LiveFunction
import dev.brikk.house.sql.intellij.catalog.LiveInventory
import dev.brikk.house.sql.intellij.catalog.LiveMergePolicy
import dev.brikk.house.sql.intellij.catalog.RefreshLiveCatalogAction
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.sort.doris.DorisDbms
import kotlinx.coroutines.CoroutineScope

object DorisFunctionHarvester : LiveCatalogHarvester {
    override fun harvest(rows: CatalogRowSource): Harvest? {
        val names = buildSet {
            rows.forEachRow("SHOW BUILTIN FUNCTIONS") { row ->
                row.string(1)?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            }
        }
        if (names.isEmpty()) return null
        return Harvest(LiveInventory(null, names.map { LiveFunction(FunctionDef(it, FunctionKind.SCALAR)) }, signatures = false))
    }
}

object DorisLiveCatalogProfile : LiveCatalogProfile {
    override val dbms = DorisDbms.DORIS
    override val productLabel = "Doris"
    override val metadata = DORIS_METADATA
    override val harvester = DorisFunctionHarvester
    override val trigger = HarvestTrigger.ONCE_PER_SESSION
    override val mergePolicy = LiveMergePolicy.FILTER_AND_EXTEND
    override val dataSources = dorisDataSourceResolver
    override val notificationGroupId = "Doris Functions"
    override fun baseline(snapshot: MetadataSnapshot) = DorisCompletionProfile.baseline
}

class DorisLiveCatalogService(scope: CoroutineScope) : LiveCatalogService(scope) {
    override val profile = DorisLiveCatalogProfile
}

class DorisFunctionHarvestInterceptor : HarvestConnectionInterceptor(DorisLiveCatalogService::class.java)
class DorisLiveCatalogCleanup : LiveCatalogCleanup(DorisLiveCatalogService::class.java)
class DorisRefreshFunctionsAction : RefreshLiveCatalogAction(DorisLiveCatalogService::class.java)
