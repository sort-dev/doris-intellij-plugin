package dev.sort.doris.sql

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
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.catalog.DorisCatalogProfile
import dev.sort.doris.catalog.DorisCatalogScopes
import dev.sort.doris.sqlResolveInspection
import dev.sort.doris.testing.productionReplay

/**
 * Minimized, anonymized shapes from a scan of real Doris SQL (ExternalSqlScanTest): each highlights
 * with no error or warning through the production registrations on a multi-catalog model.
 */
class DorisRealSqlFindingsTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var source: LocalDataSource
    private var previousFlag: String? = null

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        HeldIntrospections(testRootDisposable)
        previousFlag = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
        productionReplay(testRootDisposable)
        val model = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
        val db = ((model as BasicModModel).root as MsRoot).databases.createOrGet(1L).apply { name = "internal" }.schemas.createOrGet("db")
        mapOf("facts" to listOf("k", "x", "v", "ts"), "users" to listOf("id", "name"), "blocks" to listOf("uid", "data")).forEach { (table, columns) ->
            db.tables.createOrGet(table).columns.apply { columns.forEach { createOrGet(it) } }
        }
        source = LocalDataSource().apply {
            name = "Doris real SQL findings"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:doris-findings"; isAutoSynchronize = false; isKeepAlive = false
            this.model = model
            introspectionScope = CatalogScopes.widen(DorisCatalogScopes.multiCatalogDefaultScope(),
                CatalogScopes.select(DorisCatalogProfile.levels, listOf("internal", "db")))
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        settle()
    }

    override fun tearDown() = RunAll(
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

    private fun reports(sql: String): List<String> {
        val file = myFixture.configureByText("finding.sql", sql)
        SqlResolveMappings.getInstance(project).setMapping(file.virtualFile,
            TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(source.uniqueId)))))
        return RecordingConsole(project, file.virtualFile, source).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("internal", ObjectKind.DATABASE).append("db", ObjectKind.SCHEMA)))
            settle()
            myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WARNING }.map {
                "${it.severity} '${myFixture.editor.document.text.substring(it.startOffset, it.endOffset)}' ${it.description}"
            }
        }
    }

    /** The MySQL substrate parses [statements] without error elements, so later clauses and CTEs keep their PSI. */
    private fun assertParses(vararg statements: String) {
        for (sql in statements) {
            val file = myFixture.configureByText("parse.sql", sql)
            val errors = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.PsiErrorElement::class.java)
            assertEmpty("$sql\n${errors.map { "${it.textOffset}: ${it.errorDescription}" }}", errors.toList())
        }
    }

    private fun assertQuiet(vararg statements: String) {
        val failures = statements.associateWith(::reports).filterValues { it.isNotEmpty() }
        assertTrue(failures.entries.joinToString("\n") { (sql, found) -> "$sql\n  $found" }, failures.isEmpty())
    }

    fun testMysqlKeywordsAsAliases() {
        val sql = "WITH a AS (SELECT k, CASE WHEN v > 0 THEN 'Y' ELSE 'N' END AS signal, RANK() OVER (ORDER BY v) AS rank FROM db.facts), " +
            "b AS (SELECT * FROM a) SELECT b.signal, b.rank FROM b"
        assertParses(sql, "SELECT CAST(v AS CHAR) AS c, CAST(v AS SIGNED) AS s, CAST(ts AS DATE) AS d FROM db.facts")
        assertQuiet(sql)
    }

    fun testFullOuterJoinOfDerivedTables() = assertQuiet(
        "SELECT COALESCE(a.k, b.k) AS k, a.v, b.v FROM (SELECT * FROM db.facts WHERE x = 'A') a " +
            "FULL OUTER JOIN (SELECT * FROM db.facts WHERE x = 'B') b ON b.k = a.k",
    )

    fun testAntiAndSemiJoinsKeepLaterJoins() = assertQuiet(
        "SELECT f.k, u.name FROM db.facts f LEFT ANTI JOIN db.blocks bl ON bl.uid = f.k JOIN db.users u ON u.id = f.k",
        "SELECT f.k, u.name FROM db.facts f LEFT SEMI JOIN db.blocks bl ON bl.uid = f.k LEFT JOIN db.users u ON u.id = f.k",
    )

    fun testCtesAfterAntiJoinAndLateralView() = assertQuiet(
        "WITH a AS (SELECT k FROM db.facts), b AS (SELECT f.k FROM db.facts f LEFT ANTI JOIN (SELECT k FROM a) t ON t.k = f.k) " +
            "SELECT * FROM a UNION ALL SELECT * FROM b",
        "WITH s AS (SELECT data FROM db.blocks), e AS (SELECT CAST(x AS BIGINT) AS id FROM s " +
            "LATERAL VIEW EXPLODE_JSON_ARRAY_STRING(JSON_EXTRACT(data, '$.a')) j AS x), n AS (SELECT COUNT(*) AS c FROM db.users) " +
            "SELECT e.id, n.c FROM e CROSS JOIN n",
    )

    fun testLateralViewsParse() = assertParses(
        "SELECT x FROM db.blocks LATERAL VIEW EXPLODE_JSON_ARRAY_STRING(data) j AS x",
        "WITH s AS (SELECT data FROM db.blocks), e AS (SELECT 'A' AS kind, CAST(x AS LARGEINT) AS id FROM s " +
            "LATERAL VIEW EXPLODE_JSON_ARRAY_STRING(JSON_EXTRACT(data, '$.a')) j AS x UNION ALL SELECT 'B', CAST(x AS LARGEINT) FROM s " +
            "LATERAL VIEW EXPLODE_JSON_ARRAY_STRING(JSON_EXTRACT(data, '$.b')) j AS x) SELECT * FROM e",
    )

    fun testDorisJoinsParse() = assertParses(
        "SELECT a.k FROM db.facts a FULL OUTER JOIN db.facts b ON b.k = a.k",
        "SELECT a.k FROM db.facts a FULL JOIN db.facts b ON b.k = a.k LEFT ANTI JOIN db.blocks c ON c.uid = a.k RIGHT SEMI JOIN db.users u ON u.id = a.k",
    )

    fun testLateralViewInAUnionBranchKeepsLaterCtes() = assertQuiet(
        "WITH s AS (SELECT data FROM db.blocks), e AS (SELECT 'A' AS kind, CAST(x AS LARGEINT) AS id FROM s " +
            "LATERAL VIEW EXPLODE_JSON_ARRAY_STRING(JSON_EXTRACT(data, '$.a')) j AS x UNION ALL SELECT 'B', CAST(x AS LARGEINT) FROM s " +
            "LATERAL VIEW EXPLODE_JSON_ARRAY_STRING(JSON_EXTRACT(data, '$.b')) j AS x), n AS (SELECT COUNT(*) AS c FROM db.users) " +
            "SELECT e.kind, e.id, n.c FROM e CROSS JOIN n",
    )

    fun testCreateTableRefersToItsBacktickedColumns() = assertQuiet(
        "CREATE TABLE db.t2 (`k` BIGINT, `tags` ARRAY<VARCHAR(20)> NOT NULL, INDEX idx_tags(tags) USING INVERTED) " +
            "DUPLICATE KEY(`k`) DISTRIBUTED BY HASH(`k`) BUCKETS 1",
        "CREATE TABLE db.t3 (k BIGINT, `year` INT, `month` INT, `day` INT) DUPLICATE KEY(k) " +
            "PARTITION BY LIST (`year`, `month`, `day`) () DISTRIBUTED BY HASH(k) BUCKETS 1",
        "CREATE TABLE db.t4 (k BIGINT, `year` INT) DUPLICATE KEY(k) PARTITION BY LIST (year) () DISTRIBUTED BY HASH(k) BUCKETS 1",
    )

    fun testSyntaxDorisSharesWithStarRocks() = assertQuiet(
        "SELECT k FROM db.facts WHERE v > 0 INTO OUTFILE 's3://bucket/prefix-' FORMAT AS PARQUET PROPERTIES (\"k\" = \"v\")",
        "SELECT GROUP_CONCAT(CONCAT('''', x, '''') SEPARATOR ',\\n  ') FROM db.facts WHERE v = 1",
        "CREATE TABLE db.t5 (k BIGINT, ts DATETIME DEFAULT CURRENT_TIMESTAMP) DUPLICATE KEY(k) PARTITION BY RANGE(ts) () " +
            "DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES (\"dynamic_partition.enable\" = \"true\", \"dynamic_partition.time_unit\" = \"DAY\")",
        "SELECT k, SUM(v) AS total FROM db.facts GROUP BY ROLLUP(k, x)",
    )

    fun testInsertIntoATableFunction() = assertQuiet(
        "INSERT INTO s3(\"uri\" = \"s3://bucket/path/\", \"format\" = \"parquet\") SELECT k, v FROM db.facts",
    )

    fun testCastToAParameterizedArrayKeepsLaterCtes() {
        assertParses("SELECT CAST(x AS ARRAY<VARCHAR(120)>) AS xs FROM db.facts")
        assertQuiet("INSERT OVERWRITE TABLE db.facts PARTITION(*) WITH a AS (SELECT k, CAST(MIN_BY(x, ts) AS ARRAY<VARCHAR(120)>) AS xs " +
            "FROM db.facts GROUP BY k), b AS (SELECT * FROM a) SELECT * FROM b")
    }

    fun testInsertOverwriteWithCtes() = assertQuiet(
        "INSERT OVERWRITE TABLE db.facts PARTITION(*) WITH a AS (SELECT * FROM db.facts), b AS (SELECT * FROM a) SELECT * FROM b",
    )

    fun testPartitionNamesAreNotModelObjects() = assertQuiet(
        "ALTER TABLE db.facts DROP PARTITION p_20240101_day",
        "SELECT k FROM db.facts PARTITION (p1)",
        "SELECT k FROM db.facts PARTITIONS (p1, p2) WHERE v > 0",
    )

    fun testTimestampLiteralWithTimeZone() = assertQuiet(
        "SELECT k FROM db.facts WHERE ts >= TIMESTAMP '2026-01-01 00:00:00 UTC'",
        "SELECT k FROM db.facts WHERE ts < TIMESTAMP '2026-01-01 08:00:00 +08:00'",
    )

    fun testABrokenDatetimeLiteralStaysReported() {
        assertNotEmpty(reports("SELECT k FROM db.facts WHERE ts >= TIMESTAMP '2026-01-01 00:00:00 nonsense here'"))
    }
}
