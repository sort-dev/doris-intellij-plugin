package dev.sort.doris.pipes

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ModelTextStorage
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.model.basic.BasicSourceAware
import com.intellij.database.model.properties.CompositeText
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectNamePart
import com.intellij.database.util.LoaderContext
import com.intellij.database.util.TreePatternUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms
import dev.sort.doris.catalog.DorisCatalogScopes
import java.util.concurrent.CompletableFuture

class DorisPipesAutoIntrospectTest : BasePlatformTestCase() {
    fun testPlatformSchedulingApiLinksAndPreservesScopeAndOneShotBehavior() {
        val requested = CompletableFuture<LoaderContext>()
        // Use the platform's executor seam: real coroutine/queue, but never open a JDBC session.
        DataSourceSyncManager.withDefaultExecutor(testRootDisposable) { syncTask, _, _, _ ->
            requested.complete(syncTask.context)
        }
        val local = LocalDataSource().apply {
            name = "Offline auto-introspection compatibility test"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:doris-auto-introspection"
            isAutoSynchronize = false
            isKeepAlive = false
            introspectionScope = DorisCatalogScopes.multiCatalogDefaultScope()
        }
        val manager = LocalDataSourceManager.getInstance(project)
        manager.addDataSource(local)
        try {
            DbPsiFacade.getInstance(project).clearCaches()
            val model = ModelFactory(NoopStorage()).createModel(DorisDbms.DORIS)
            val catalog = (model.root as MsRoot).databases.createOrGet(1L).apply { name = "warehouse" }
            val schema = catalog.schemas.createOrGet("target_db")

            assertTrue(DorisPipesAutoIntrospect.request(project, local, "warehouse", "target_db", schema))
            // Await actual executor entry, not just launch: catches a removed scheduling API.
            val context = PlatformTestUtil.waitForFuture(requested, 10_000)
            assertEquals(1, context.countTasks())
            assertEquals("warehouse", context.tasks.single().databaseName)
            val scope = TreePatternUtils.serialize(local.introspectionScope)
            assertTrue(scope, "internal" in scope)
            assertTrue(scope, "warehouse" in scope && "target_db" in scope)
            assertFalse(DorisPipesAutoIntrospect.request(project, local, "warehouse", "target_db", schema))
            assertEquals(scope, TreePatternUtils.serialize(local.introspectionScope))
            PlatformTestUtil.waitWithEventsDispatching("Introspection queue must finish before removing its source",
                { !DataSourceSyncManager.getInstance().isActive(local) }, 10)
        } finally {
            manager.removeDataSource(local)
            DbPsiFacade.getInstance(project).clearCaches()
        }
    }

    private class NoopStorage : ModelTextStorage {
        override fun handleRename(element: BasicElement, oldName: ObjectNamePart) = Unit
        override fun save(element: BasicSourceAware, text: CompositeText?) = Unit
        override fun queueDelete(element: BasicElement) = Unit
        override fun load(element: BasicSourceAware): CompositeText? = null
        override fun getVersion(element: BasicElement): Long? = null
        override fun setVersion(element: BasicElement, version: Long?) = Unit
        override fun writeSession(model: BasicModModel, runnable: Runnable) = runnable.run()
        override fun flushQueues() = Unit
        override fun clear() = Unit
    }
}
