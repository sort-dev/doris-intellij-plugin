package dev.sort.doris.catalog

import com.intellij.database.dataSource.DataSourceBriefConfig
import com.intellij.database.dataSource.DatabaseConnectivityConfiguration
import com.intellij.database.dataSource.DbOptionProvider
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.introspection.DBIntrospectionContext
import com.intellij.database.layoutedQueries.DBCommandRunner
import com.intellij.database.layoutedQueries.DBQueryRunner
import com.intellij.database.layoutedQueries.DBScriptRunner
import com.intellij.database.layoutedQueries.DBTransaction
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ModelTextStorage
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.model.basic.BasicSourceAware
import com.intellij.database.model.properties.CompositeText
import com.intellij.database.model.properties.Level
import com.intellij.database.remote.jdba.core.ResultLayout
import com.intellij.database.remote.jdba.sql.SqlCommand
import com.intellij.database.remote.jdba.sql.SqlQuery
import com.intellij.database.remote.jdba.sql.SqlScript
import com.intellij.database.util.ObjectNamePart
import com.intellij.database.util.TreePattern
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path

/** B5: a catalog query failure must abort before the platform's destructive family sweep. */
class DorisCatalogFailurePreservationTest : BasePlatformTestCase() {
    private lateinit var model: BasicModModel
    private lateinit var lister: Any

    override fun setUp() {
        super.setUp()
        val factory = ModelFactory(NoopStorage())
        model = factory.createModel(DorisDbms.DORIS)
        val introspector = DorisIntrospector(context(), DorisDbms.DORIS, factory)
        introspector.init(model, config(), TreePattern.EMPTY)
        lister = introspector.javaClass.getDeclaredMethod("createDatabaseLister").apply { isAccessible = true }
            .invoke(introspector)
    }

    fun testThrownNullCancellationAndMalformedInventoriesPreserveCompleteCachedHierarchy() {
        val queryFailure = IllegalStateException("catalog connection failed")
        val cancellation = ProcessCanceledException()
        for ((outcome, expected) in listOf(
            Pair<() -> Any?, Throwable?>({ throw queryFailure }, queryFailure),
            Pair<() -> Any?, Throwable?>({ null }, null),
            Pair<() -> Any?, Throwable?>({ throw cancellation }, cancellation),
            Pair<() -> Any?, Throwable?>({ listOf(row(1, null)) }, null),
            Pair<() -> Any?, Throwable?>({ listOf(row(1, " ")) }, null),
            Pair<() -> Any?, Throwable?>({ listOf(row(1, "internal"), row(2, "INTERNAL")) }, null),
            Pair<() -> Any?, Throwable?>({ listOf(row(1, "internal"), row(1, "external")) }, null),
        )) {
            val cached = seedCachedHierarchy()
            val transaction = RecordingTransaction(outcome)
            val failure = try {
                applyLister(transaction)
                fail("Expected catalog enumeration to fail")
                error("unreachable")
            } catch (error: Exception) {
                error
            }
            val root = model.root as MsRoot
            assertSame(cached.catalog, root.databases.get("internal"))
            assertSame(cached.database, cached.catalog.schemas.get("cached_db"))
            assertSame(cached.table, cached.database.tables.get("cached_table"))
            assertEquals(listOf("SHOW CATALOGS"), transaction.queries)
            assertTrue("the stock query helper must close its runner", transaction.closes > 0)
            if (expected != null) assertSame(expected, failure)
            if (failure is InvocationTargetException) fail("reflection wrapper was not unwrapped: $failure")
            clearCatalogs()
        }
    }

    fun testSuccessfulEmptyInventoryRemovesAllCachedCatalogs() {
        seedCachedHierarchy()
        val transaction = RecordingTransaction { emptyList<DorisCatalogQueries.CatalogRow>() }
        applyLister(transaction)
        assertTrue((model.root as MsRoot).databases.isEmpty())
        assertTrue(transaction.closes > 0)
    }

    fun testSuccessfulInventoryReplacesMissingCatalogsAndKeepsNestedDataForRenewedCatalog() {
        val cached = seedCachedHierarchy()
        val external = (model.root as MsRoot).databases.createOrGet(2L).apply { name = "external" }
        external.schemas.createOrGet("old_db").tables.createOrGet("old_table")
        val transaction = RecordingTransaction { listOf(row(0, "internal"), row(3, "replacement")) }
        applyLister(transaction)
        val catalogs = (model.root as MsRoot).databases
        assertEquals(listOf("internal", "replacement"), catalogs.map { it.name })
        assertSame(cached.catalog, catalogs.get("internal"))
        val renewedDatabase = catalogs.get("internal")!!.schemas.get("cached_db")
        assertSame(cached.database, renewedDatabase)
        assertSame(cached.table, renewedDatabase!!.tables.get("cached_table"))
        assertNull(catalogs.get("external"))
        assertTrue(transaction.closes > 0)
    }

    private data class Cached(val catalog: com.intellij.database.dialects.mssql.model.MsDatabase,
                              val database: com.intellij.database.dialects.mssql.model.MsSchema,
                              val table: com.intellij.database.dialects.mssql.model.MsTable)

    private fun seedCachedHierarchy(): Cached {
        val catalog = (model.root as MsRoot).databases.createOrGet(0L).apply { name = "internal" }
        val database = catalog.schemas.createOrGet("cached_db")
        val table = database.tables.createOrGet("cached_table")
        return Cached(catalog, database, table)
    }

    private fun clearCatalogs() {
        val catalogs = (model.root as MsRoot).databases
        catalogs.markChildrenAsSyncPending()
        catalogs.removeSyncPendingChildren()
    }

    private fun applyLister(transaction: DBTransaction) {
        val method = lister.javaClass.methods.single { it.name.startsWith("listAndApplyDatabases") }
        try {
            method.invoke(lister, transaction)
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }
    }

    private fun row(id: Long, name: String?) = DorisCatalogQueries.CatalogRow().apply {
        CatalogId = id
        CatalogName = name
        IsCurrent = if (name.equals("internal", ignoreCase = true)) "Yes" else "No"
    }

    private fun context() = object : DBIntrospectionContext {
        override val project get() = this@DorisCatalogFailurePreservationTest.project
        override fun getUserNotifier(actionName: String) = object : DBIntrospectionContext.UserNotifier {
            override fun notifyLevelAutoSelection(
                dataSourceName: String,
                schemas: Collection<com.intellij.database.model.basic.BasicMultiLevelSchema>,
            ) = Unit
            override fun notifyDiagnosticMonitorFile(
                type: DBIntrospectionContext.DiagnosticNotificationEventType,
                file: Path,
            ) = Unit
        }
    }

    private fun config() = object : DataSourceBriefConfig {
        override val uniqueId = "offline-catalog-preservation"
        override val name = "Offline catalog preservation test"
        override val dbms = DorisDbms.DORIS
        override val boundTo = ObjectKind.ROOT
        override val boundStr = ""
        override val isRewriteBounds = false
        override val introspectionScope = TreePattern.EMPTY
        override val introspectionLevel = Level.L3
        override fun <T : Any?> getProvidedOptionValue(
            providerClass: Class<out DbOptionProvider<out DatabaseConnectivityConfiguration, T>>,
        ): T? = null
    }

    private class RecordingTransaction(private val outcome: () -> Any?) : DBTransaction {
        val queries = mutableListOf<String>()
        var closes = 0

        @Suppress("UNCHECKED_CAST")
        override fun <S : Any?> query(query: SqlQuery<S>): DBQueryRunner<S> = object : DBQueryRunner<S> {
            override fun withParams(vararg params: Any?) = this
            override fun packBy(packSize: Int) = this
            override fun run(): S {
                queries += query.sourceText
                return outcome() as S
            }
            override fun start() = Unit
            override fun nextPack(): S = unsupported()
            override fun close() { closes++ }
            override fun <I : Any?> getSpecificService(serviceClass: Class<I>, serviceName: String): I = unsupported()
        }

        override fun command(command: String): DBCommandRunner = unsupported()
        override fun command(command: SqlCommand): DBCommandRunner = unsupported()
        override fun <S : Any?> query(queryText: String, layout: ResultLayout<S>): DBQueryRunner<S> = unsupported()
        override fun script(script: SqlScript): DBScriptRunner = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by catalog lister test")
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
