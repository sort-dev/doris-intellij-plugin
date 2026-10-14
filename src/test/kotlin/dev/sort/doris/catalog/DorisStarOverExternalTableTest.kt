package dev.sort.doris.catalog

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectName
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.TreePattern
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.HeldIntrospections
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.sql.DorisSqlDialect
import dev.sort.doris.sqlResolveInspection
import dev.sort.doris.testing.productionReplay

/**
 * Columns a `*` brings through a derived table or CTE over an external-catalog table, in every state
 * the multi-catalog model can hold that table in. Names are neutral stand-ins for a dogfood report.
 */
class DorisStarOverExternalTableTest : BasePlatformTestCase() {
    private enum class Situation { INTROSPECTED, COLUMNLESS, SCHEMA_WITHOUT_TABLE, CATALOG_EMPTY }

    private val shapes = listOf(
        "WITH c AS (SELECT * FROM (SELECT FROM_SECOND(ts) AS ts, * EXCEPT(ts), 0 AS k FROM ext_catalog.archive_db.facts) AS u) SELECT ts, uid FROM c",
        "WITH c AS (SELECT * FROM (SELECT FROM_SECOND(ts) AS ts1, *, 0 AS k FROM ext_catalog.archive_db.facts) AS u) SELECT ts1, uid FROM c",
        "SELECT uid, * FROM (SELECT FROM_SECOND(ts) AS ts1, *, 0 AS k FROM ext_catalog.archive_db.facts) AS u",
        // An unaliased three-part table qualifies its columns by its own name.
        "SELECT facts.uid, MAX(facts.ts) AS last_ts FROM ext_catalog.archive_db.facts GROUP BY facts.uid",
        // A view body Doris accepts without AS.
        "CREATE OR REPLACE VIEW internal.app.v WITH c AS (SELECT * FROM ext_catalog.archive_db.facts) SELECT uid, kind FROM c",
        // Statements wrapping a query: EXPLAIN, a scheduled job's DML, an export through a table function.
        "EXPLAIN WITH a AS (SELECT facts.uid, facts.ts FROM ext_catalog.archive_db.facts) SELECT uid FROM a WHERE ts > 0",
        "CREATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO internal.app.t SELECT * EXCEPT(kind) FROM ext_catalog.archive_db.facts WHERE uid = 1",
        "INSERT INTO s3(\"uri\" = \"s3://bucket/path/\", \"format\" = \"csv\") SELECT ts, uid FROM ext_catalog.archive_db.facts WHERE kind = 'a'",
    )
    private lateinit var errors: BackgroundErrors
    private var previousFlag: String? = null
    private var source: LocalDataSource? = null

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        // Highlighting alone: the references ask for no introspection here (DorisMultiCatalogFlowTest covers that).
        HeldIntrospections(testRootDisposable)
        System.setProperty(DorisReferenceIntrospectAnnotator.PROPERTY, "false")
        com.intellij.openapi.util.Disposer.register(testRootDisposable) { System.clearProperty(DorisReferenceIntrospectAnnotator.PROPERTY) }
        productionReplay(testRootDisposable)
        previousFlag = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
    }

    override fun tearDown() = RunAll(
        { removeSource() },
        { errors.awaitScopes() }, { errors.assertNone() },
        { previousFlag?.let { System.setProperty(DorisCatalogs.PROPERTY, it) } ?: System.clearProperty(DorisCatalogs.PROPERTY) },
        { super.tearDown() },
    ).run()

    private fun removeSource() {
        source?.let { LocalDataSourceManager.getInstance(project).removeDataSource(it) }
        source = null
        DbPsiFacade.getInstance(project).clearCaches()
    }

    /** Queued indexing must not enter dumb mode in the middle of a highlighting pass. */
    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun model(situation: Situation) {
        removeSource()
        val created = LocalDataSource().apply {
            name = "Offline Doris catalogs $situation"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:doris-star"; isAutoSynchronize = false; isKeepAlive = false
            model = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
            introspectionScope = DorisCatalogScopes.multiCatalogDefaultScope()
        }
        val root = (created.model as BasicModModel).root as MsRoot
        root.databases.createOrGet(1L).apply { name = "internal" }.schemas.createOrGet("app").tables.createOrGet("t").columns.createOrGet("id")
        val catalog = root.databases.createOrGet(2L).apply { name = "ext_catalog" }
        if (situation != Situation.CATALOG_EMPTY) {
            val schema = catalog.schemas.createOrGet("archive_db")
            created.introspectionScope = CatalogScopes.widen(created.introspectionScope, CatalogScopes.select(DorisCatalogProfile.levels, listOf("ext_catalog", "archive_db")))
            if (situation != Situation.SCHEMA_WITHOUT_TABLE) schema.tables.createOrGet("facts").apply {
                if (situation == Situation.INTROSPECTED) for (column in listOf("ts", "uid", "kind")) columns.createOrGet(column)
            }
        }
        LocalDataSourceManager.getInstance(project).addDataSource(created)
        source = created
        DbPsiFacade.getInstance(project).clearCaches()
        settle()
    }

    private fun errorsIn(sql: String): List<String> {
        val file = myFixture.configureByText("star.sql", sql)
        SqlResolveMappings.getInstance(project).setMapping(file.virtualFile,
            TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(source!!.uniqueId)))))
        settle()
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description.orEmpty() }
    }

    fun testStarColumnsThroughDerivedTablesAndCtesAreNeverFalseErrors() {
        val failures = Situation.entries.flatMap { situation ->
            model(situation)
            shapes.mapNotNull { sql -> errorsIn(sql).takeIf { it.isNotEmpty() }?.let { "$situation: $sql -> $it" } }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    fun testAMissingColumnOfAnIntrospectedTableStaysRed() {
        model(Situation.INTROSPECTED)
        val red = errorsIn("SELECT nope FROM (SELECT ts, uid FROM ext_catalog.archive_db.facts) AS u")
        assertTrue(red.toString(), red.any { "nope" in it })
    }

    fun testAMissingTableOfAnIntrospectedSchemaStaysRed() {
        model(Situation.INTROSPECTED)
        val red = errorsIn("SELECT * FROM ext_catalog.archive_db.nope")
        assertTrue(red.toString(), red.any { "nope" in it })
    }
}
