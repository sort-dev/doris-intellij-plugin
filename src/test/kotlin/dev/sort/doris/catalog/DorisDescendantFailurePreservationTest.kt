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

    private fun retrieveDatabases(transaction: DBTransaction) {
        val retriever = introspector.javaClass.getDeclaredMethod(
            "createDatabaseRetriever",
            DBTransaction::class.java,
            MsDatabase::class.java,
        ).apply { isAccessible = true }.invoke(introspector, transaction, catalog)
        retriever.javaClass.getMethod("retrieveSchemas").invoke(retriever)
    }

    private fun retrieveObjects(transaction: DBTransaction) {
        val retriever = introspector.javaClass.getDeclaredMethod(
            "createSchemaRetriever",
            DBTransaction::class.java,
            MsSchema::class.java,
        ).apply { isAccessible = true }.invoke(introspector, transaction, database)
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
