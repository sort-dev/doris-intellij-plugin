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

/** The shared real-SQL contract through Doris's production registrations, on a multi-catalog model. */
class DorisRealSqlContractTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var source: LocalDataSource
    private var console: RecordingConsole? = null
    private var previousFlag: String? = null

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        HeldIntrospections(testRootDisposable)
        previousFlag = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
        val model = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
        val root = (model as BasicModModel).root as MsRoot
        var id = 0L
        fun table(path: String) = path.split('.').let { (catalog, schema, name) ->
            (root.databases.firstOrNull { it.name == catalog } ?: root.databases.createOrGet(++id).apply { this.name = catalog })
                .schemas.createOrGet(schema).tables.createOrGet(name)
        }
        table("internal.app.t").columns.createOrGet("id")
        for ((path, columns) in RealSqlModel.STANDARD.tables) table(path).apply { columns.forEach { this.columns.createOrGet(it) } }
        RealSqlModel.STANDARD.bare.forEach(::table)
        val namespaces = (RealSqlModel.STANDARD.tables.keys + RealSqlModel.STANDARD.bare).map { it.split('.').dropLast(1) }.distinct()
        source = LocalDataSource().apply {
            name = "Doris real SQL"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:doris-real-sql"; isAutoSynchronize = false; isKeepAlive = false
            this.model = model
            introspectionScope = namespaces.map { CatalogScopes.select(DorisCatalogProfile.levels, it) }
                .fold(DorisCatalogScopes.multiCatalogDefaultScope(), CatalogScopes::widen)
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
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
}
