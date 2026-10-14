package dev.sort.doris.sql

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.util.TreePattern
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.HeldIntrospections
import dev.brikk.house.sql.intellij.testing.RealSqlContract
import dev.brikk.house.sql.intellij.testing.RealSqlFeature
import dev.brikk.house.sql.intellij.testing.RealSqlModel
import dev.brikk.house.sql.intellij.testing.RealSqlTarget
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.catalog.DorisCatalogProfile
import dev.sort.doris.catalog.DorisCatalogScopes
import dev.sort.doris.sqlResolveInspection
import java.util.UUID
import dev.sort.doris.testing.productionReplay

/** The shared real-SQL contract through Doris's production registrations, on a multi-catalog model. */
class DorisRealSqlContractTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var source: LocalDataSource
    private var console: RecordingConsole? = null
    private var previousFlag: String? = null

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        productionReplay(testRootDisposable)
        HeldIntrospections(testRootDisposable)
        previousFlag = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        install(RealSqlModel.STANDARD)
    }

    /** A multi-catalog data source holding [model]: introspected tables, tables listed without columns, namespaces listed but never loaded. */
    private fun install(model: RealSqlModel) {
        if (::source.isInitialized) {
            console?.close()
            console = null
            LocalDataSourceManager.getInstance(project).removeDataSource(source)
        }
        val created = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
        val root = (created as BasicModModel).root as MsRoot
        var id = 0L
        fun catalog(name: String) = root.databases.firstOrNull { it.name == name } ?: root.databases.createOrGet(++id).apply { this.name = name }
        fun table(path: String) = path.split('.').let { (catalog, schema, name) -> catalog(catalog).schemas.createOrGet(schema).tables.createOrGet(name) }
        table("internal.app.t").columns.createOrGet("id")
        for ((path, columns) in model.tables) table(path).apply { columns.forEach { this.columns.createOrGet(it) } }
        model.bare.forEach(::table)
        for (namespace in model.namespaces) namespace.split('.').let { parts ->
            val catalog = catalog(parts[0])
            if (parts.size > 1) catalog.schemas.createOrGet(parts[1])
        }
        val introspected = (model.tables.keys + model.bare).map { it.split('.').dropLast(1) }.distinct()
        source = LocalDataSource().apply {
            name = "Doris real SQL"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:doris-real-sql"; isAutoSynchronize = false; isKeepAlive = false
            this.model = created
            introspectionScope = introspected.map { CatalogScopes.select(DorisCatalogProfile.levels, it) }
                .fold(DorisCatalogScopes.multiCatalogDefaultScope(), CatalogScopes::widen)
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        settle()
    }

    override fun tearDown() = RunAll(
        { LookupManager.getInstance(project).hideActiveLookup() },
        { console?.close() },
        { errors.awaitScopes() }, { errors.assertNone() },
        { LocalDataSourceManager.getInstance(project).removeDataSource(source) },
        { DbPsiFacade.getInstance(project).clearCaches() },
        { previousFlag?.let { System.setProperty(DorisCatalogs.PROPERTY, it) } ?: System.clearProperty(DorisCatalogs.PROPERTY) },
        { super.tearDown() },
    ).run()

    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private val target = object : RealSqlTarget {
        override val dialect = "doris"
        override val features = setOf(
            RealSqlFeature.STAR_EXCEPT, RealSqlFeature.STAR_REPLACE, RealSqlFeature.GROUP_BY_ALIAS, RealSqlFeature.FULL_JOIN, RealSqlFeature.INTERSECT_EXCEPT,
            RealSqlFeature.CREATE_TABLE_AS, RealSqlFeature.THREE_PART_NAMES, RealSqlFeature.BACKTICK_IDENTIFIERS, RealSqlFeature.DORIS,
        )
        override val fixture: CodeInsightTestFixture get() = myFixture

        override fun install(model: RealSqlModel) = this@DorisRealSqlContractTest.install(model)

        override fun open(sql: String) {
            LookupManager.getInstance(project).hideActiveLookup()
            console?.close()
            val file = myFixture.configureByText("real-${UUID.randomUUID()}.sql", sql)
            SqlResolveMappings.getInstance(project).setMapping(file.virtualFile,
                TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(source.uniqueId)))))
            console = RecordingConsole(project, file.virtualFile, source).also {
                it.console.setSearchPath(SearchPath.of(ObjectPath.create("shop", ObjectKind.DATABASE).append("sales", ObjectKind.SCHEMA)))
            }
            settle()
        }
    }

    fun testEverydaySqlHasNoFalseReports() {
        RealSqlContract(target).check()
    }

    fun testExternalTableStatesNeverReportColumns() {
        RealSqlContract(target).checkModelStates()
    }
}
