package dev.sort.doris.catalog

import com.intellij.database.dataSource.DataSourceBriefConfig
import com.intellij.database.dataSource.DatabaseConnectivityConfiguration
import com.intellij.database.dataSource.DbOptionProvider
import com.intellij.database.dialects.mssql.model.MsDatabase
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.dialects.mssql.model.MsSchema
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
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms
import java.nio.file.Path

class DorisDescendantFailurePreservationTest : BasePlatformTestCase() {
    private lateinit var model: BasicModModel
    private lateinit var introspector: DorisIntrospector
    private lateinit var catalog: MsDatabase
    private lateinit var database: MsSchema

    override fun setUp() {
        super.setUp()
        val factory = ModelFactory(NoopStorage())
        model = factory.createModel(DorisDbms.DORIS)
        introspector = DorisIntrospector(context(), DorisDbms.DORIS, factory)
        introspector.init(model, config(), TreePattern.EMPTY)
        catalog = (model.root as MsRoot).databases.createOrGet(0L).apply { name = "internal" }
        database = catalog.schemas.createOrGet("cached_db")
    }

    fun testNullAndMalformedDatabaseInventoriesPreserveCachedIdentityAndCloseRunners() {
        val cachedTable = database.tables.createOrGet("cached_table")
        val outcomes = listOf<Any?>(
            null,
            arrayOf(" "),
            arrayOf("cached_db", "CACHED_DB"),
        )
        for (outcome in outcomes) {
            val tx = RecordingTransaction(mapOf(databaseQuery() to { outcome }))
            retrieveDatabases(tx)
            assertSame(database, catalog.schemas.get("cached_db"))
            assertSame(cachedTable, database.tables.get("cached_table"))
            assertEquals(tx.runnersCreated, tx.runnersClosed)
        }
    }

    fun testNullAndMalformedTableOrColumnInventoriesPreserveCachedIdentityAndCloseRunners() {
        val cachedTable = database.tables.createOrGet("cached_table")
        val cachedColumn = cachedTable.columns.createOrGet("cached_column")
        val validTable = tableRow("cached_table")
        val validColumn = columnRow("cached_table", "cached_column", 1)
        val malformed = listOf(
            Pair<Any?, Any?>(null, listOf(validColumn)),
            Pair(listOf(tableRow(" ")), listOf(validColumn)),
            Pair(listOf(validTable, tableRow("CACHED_TABLE")), listOf(validColumn)),
            Pair<Any?, Any?>(listOf(validTable), null),
            Pair(listOf(validTable), listOf(columnRow("cached_table", " ", 1))),
            Pair(listOf(validTable), listOf(columnRow("missing_table", "c", 1))),
            Pair(listOf(validTable), listOf(validColumn, columnRow("CACHED_TABLE", "CACHED_COLUMN", 2))),
            Pair(listOf(validTable), listOf(validColumn, columnRow("cached_table", "other", 1))),
        )

        for ((tables, columns) in malformed) {
            val tx = RecordingTransaction(
                mapOf(
                    tablesQuery() to { tables },
                    columnsQuery() to { columns },
                ),
            )
            retrieveObjects(tx)
            assertSame(cachedTable, database.tables.get("cached_table"))
            assertSame(cachedColumn, cachedTable.columns.get("cached_column"))
            assertEquals(tx.runnersCreated, tx.runnersClosed)
        }
    }

    fun testSuccessfulEmptyDatabaseInventoryRemovesCachedDatabases() {
        val tx = RecordingTransaction(mapOf(databaseQuery() to { emptyArray<String>() }))
        retrieveDatabases(tx)
        assertTrue(catalog.schemas.isEmpty())
        assertEquals(tx.runnersCreated, tx.runnersClosed)
    }

    fun testSuccessfulEmptyObjectInventoriesRemoveCachedObjects() {
        database.tables.createOrGet("cached_table").columns.createOrGet("cached_column")
        val tx = RecordingTransaction(
            mapOf(
                tablesQuery() to { emptyList<DorisCatalogQueries.TableRow>() },
                columnsQuery() to { emptyList<DorisCatalogQueries.ColumnRow>() },
            ),
        )
        retrieveObjects(tx)
        assertTrue(database.tables.isEmpty())
        assertTrue(database.views.isEmpty())
        assertEquals(tx.runnersCreated, tx.runnersClosed)
    }

    fun testSuccessfulEmptyColumnInventoryKeepsTableIdentityAndRemovesCachedColumns() {
        val cachedTable = database.tables.createOrGet("cached_table")
        cachedTable.columns.createOrGet("cached_column")
        val tx = RecordingTransaction(
            mapOf(
                tablesQuery() to { listOf(tableRow("cached_table")) },
                columnsQuery() to { emptyList<DorisCatalogQueries.ColumnRow>() },
            ),
        )
        retrieveObjects(tx)
        assertSame(cachedTable, database.tables.get("cached_table"))
        assertTrue(cachedTable.columns.isEmpty())
        assertEquals(tx.runnersCreated, tx.runnersClosed)
    }

    fun testLoadedTableHasStructureDetailsAndOriginalDefinition() {
        val ddl = "CREATE TABLE acme_orders (id BIGINT) ENGINE=OLAP DUPLICATE KEY(id) " +
            "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES (\"replication_num\"=\"1\")"
        val tx = RecordingTransaction(mapOf(
            tablesQuery() to { listOf(tableRow("acme_orders")) },
            columnsQuery() to { listOf(columnRow("acme_orders", "id", 1)) },
            "SHOW CREATE TABLE `internal`.`cached_db`.`acme_orders`" to {
                arrayOf<Any>("acme_orders", ddl)
            },
        ))
        val retriever = introspector.javaClass.getDeclaredMethod(
            "createSchemaRetriever", DBTransaction::class.java, MsSchema::class.java,
        ).apply { isAccessible = true }.invoke(introspector, tx, database)
        retriever.javaClass.getMethod("setWithSources", Boolean::class.javaPrimitiveType)
            .invoke(retriever, true)
        retriever.javaClass.getMethod("process").invoke(retriever)
        val table = checkNotNull(database.tables.get("acme_orders"))
        assertEquals("source-loaded tables must stop requesting elevation", Level.L3, table.detailsLevel)
        val task = com.intellij.database.script.generator.ScriptingSingleModelTaskBuilder(
            model, com.intellij.database.script.generator.ScriptCategory.CREATE_DEFINITION,
        ).apply { addElements(listOf(table)) }.build()
        val rendered = com.intellij.database.script.generator.ScriptGenerators.byModel(model)
            .makeScript(project, task).getScriptText()
        assertEquals("the server definition must retain Doris properties", ddl, rendered.trim().trimEnd(';'))
        val generate = com.intellij.database.util.DatabaseDefinitionHelper::class.java.methods.single {
            it.name == "generateDefinitionUsingScriptingService" && it.parameterCount in 3..4
        }
        val arguments = arrayOfNulls<Any>(generate.parameterCount).apply { this[0] = project; this[1] = table }
        assertTrue("the actual DataGrip documentation/DDL helper must show the server text",
            generate.invoke(null, *arguments).toString().contains(ddl))

        val cache = project.getService(DorisOriginalDefinitions::class.java)
        val saved = com.intellij.util.xmlb.XmlSerializer.serialize(cache.state)
        cache.loadState(DorisOriginalDefinitions.Sources())
        assertNull(cache.get(table))
        cache.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(saved, DorisOriginalDefinitions.Sources::class.java))
        assertEquals("the IDE cache must survive an XML round trip", ddl, cache.get(table))
        assertEquals(tx.runnersCreated, tx.runnersClosed)

        // Reopened data sources use a new model. The DDL must survive that model replacement.
        val reopenedFactory = ModelFactory(NoopStorage())
        val reopened = reopenedFactory.createModel(DorisDbms.DORIS)
        DorisIntrospector(context(), DorisDbms.DORIS, reopenedFactory).init(reopened, config(), TreePattern.EMPTY)
        val reopenedTable = (reopened.root as MsRoot).databases.createOrGet("internal")
            .schemas.createOrGet("cached_db").tables.createOrGet("acme_orders")
        val reopenedTask = com.intellij.database.script.generator.ScriptingSingleModelTaskBuilder(
            reopened, com.intellij.database.script.generator.ScriptCategory.CREATE_DEFINITION,
        ).apply { addElements(listOf(reopenedTable)) }.build()
        assertEquals(ddl, com.intellij.database.script.generator.ScriptGenerators.byModel(reopened)
            .makeScript(project, reopenedTask).getScriptText().trim().trimEnd(';'))
    }

    fun testSourceFailureLeavesStructureUsableAndRemovesStaleDefinition() {
        val table = database.tables.createOrGet("acme_orders")
        val cache = project.getService(DorisOriginalDefinitions::class.java)
        cache.put(table, "CREATE TABLE stale_definition(id INT)")
        table.detailsLevel = Level.L3
        val tx = sourceTransaction { throw java.sql.SQLException("SHOW CREATE denied") }
        retrieveObjects(tx, true)
        assertEquals(Level.L2, table.detailsLevel)
        assertEquals(listOf("id"), table.columns.map { it.name })
        assertNull("a failed refresh must not present the old DDL as current", cache.get(table))
        assertEquals(tx.runnersCreated, tx.runnersClosed)
    }

    fun testSourceCancellationPropagatesAndClosesTheRunner() {
        val cancellation = com.intellij.openapi.progress.ProcessCanceledException()
        val tx = sourceTransaction { throw cancellation }
        try {
            retrieveObjects(tx, true)
            fail("expected cancellation")
        } catch (wrapped: java.lang.reflect.InvocationTargetException) {
            assertSame(cancellation, wrapped.targetException)
        }
        assertEquals(Level.L2, database.tables.get("acme_orders")!!.detailsLevel)
        assertEquals(tx.runnersCreated, tx.runnersClosed)
    }

    fun testStructureRefreshDoesNotFetchDefinitions() {
        val tx = sourceTransaction { error("a level 2 refresh must not fetch sources") }
        retrieveObjects(tx)
        assertEquals(Level.L2, database.tables.get("acme_orders")!!.detailsLevel)
        assertEquals(tx.runnersCreated, tx.runnersClosed)
    }

    private fun sourceTransaction(source: () -> Any?) = RecordingTransaction(mapOf(
        tablesQuery() to { listOf(tableRow("acme_orders")) },
        columnsQuery() to { listOf(columnRow("acme_orders", "id", 1)) },
        "SHOW CREATE TABLE `internal`.`cached_db`.`acme_orders`" to source,
    ))

    private fun retrieveDatabases(transaction: DBTransaction) {
        val retriever = introspector.javaClass.getDeclaredMethod(
            "createDatabaseRetriever",
            DBTransaction::class.java,
            MsDatabase::class.java,
        ).apply { isAccessible = true }.invoke(introspector, transaction, catalog)
        retriever.javaClass.getMethod("retrieveSchemas").invoke(retriever)
    }

    private fun retrieveObjects(transaction: DBTransaction, withSources: Boolean = false) {
        val retriever = introspector.javaClass.getDeclaredMethod(
            "createSchemaRetriever",
            DBTransaction::class.java,
            MsSchema::class.java,
        ).apply { isAccessible = true }.invoke(introspector, transaction, database)
        retriever.javaClass.getMethod("setWithSources", Boolean::class.javaPrimitiveType)
            .invoke(retriever, withSources)
        retriever.javaClass.getMethod("process").invoke(retriever)
    }

    private fun databaseQuery() = DorisCatalogQueries.listDatabasesIn("internal").sourceText
    private fun tablesQuery() = DorisCatalogQueries.listTablesIn("internal").sourceText
    private fun columnsQuery() = DorisCatalogQueries.listColumnsIn("internal").sourceText

    private fun tableRow(name: String) = DorisCatalogQueries.TableRow().apply {
        TABLE_NAME = name
        TABLE_TYPE = "BASE TABLE"
    }

    private fun columnRow(table: String, name: String, position: Long) = DorisCatalogQueries.ColumnRow().apply {
        TABLE_NAME = table
        COLUMN_NAME = name
        DATA_TYPE = "int"
        ORDINAL_POSITION = position
    }

    private fun context() = object : DBIntrospectionContext {
        override val project get() = this@DorisDescendantFailurePreservationTest.project
        override fun getUserNotifier(dsDisplayName: String) = object : DBIntrospectionContext.UserNotifier {
            override fun notifyLevelAutoSelection(
                databaseName: String,
                schemas: Collection<com.intellij.database.model.basic.BasicMultiLevelSchema>,
            ) = Unit

            override fun notifyDiagnosticMonitorFile(
                eventType: DBIntrospectionContext.DiagnosticNotificationEventType,
                filePath: Path,
            ) = Unit
        }
    }

    private fun config() = object : DataSourceBriefConfig {
        override val uniqueId = "offline-descendant-preservation"
        override val name = "Offline descendant preservation test"
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

    private class RecordingTransaction(
        private val outcomes: Map<String, () -> Any?>,
    ) : DBTransaction {
        var runnersCreated = 0
        var runnersClosed = 0

        @Suppress("UNCHECKED_CAST")
        override fun <S : Any?> query(query: SqlQuery<S>): DBQueryRunner<S> {
            runnersCreated++
            return object : DBQueryRunner<S> {
                private var closed = false
                override fun withParams(vararg params: Any?) = this
                override fun packBy(packSize: Int) = this
                override fun run(): S = outcomes.getValue(query.sourceText).invoke() as S
                override fun start() = Unit
                override fun nextPack(): S = unsupported()
                override fun close() {
                    if (!closed) {
                        closed = true
                        runnersClosed++
                    }
                }
                override fun <I : Any?> getSpecificService(serviceClass: Class<I>, serviceName: String): I = unsupported()
            }
        }

        override fun command(command: String): DBCommandRunner = unsupported()
        override fun command(command: SqlCommand): DBCommandRunner = unsupported()
        override fun <S : Any?> query(queryText: String, layout: ResultLayout<S>): DBQueryRunner<S> = unsupported()
        override fun script(script: SqlScript): DBScriptRunner = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used")
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
