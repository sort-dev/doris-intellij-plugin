package dev.sort.doris.catalog

import com.intellij.database.dialects.mssql.model.MsMetaModel
import com.intellij.database.dialects.mysql.model.MysqlMetaModel
import com.intellij.database.dialects.mysqlbase.introspector.MysqlBaseIntrospector
import com.intellij.database.dialects.mysqlbase.model.MysqlBaseModelHelper
import com.intellij.database.model.ObjectKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.DorisModelFacade

/**
 * Dual-mode wiring guarantees (M1, updated for the M10 default flip):
 *
 *  1. The flag defaults **ON** since 0.3.0; only an explicit `false` disables it (escape hatch).
 *  2. With the flag pinned OFF ([DorisCatalogs.setForTests]), [DorisModelFacade] returns the *exact
 *     same* MySQL model + helper the pre-catalog releases shipped, and the DORIS introspector
 *     factory advertises the stock MySQL capabilities — the escape hatch stays byte-equivalent.
 *  3. The flag-ON model ([MsMetaModel.MODEL], SQL Server family) exposes the DATABASE (catalog)
 *     level that the MySQL model collapses — the whole point of path (a).
 */
class DorisCatalogWiringTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            System.clearProperty(DorisCatalogs.PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    /** JVM-global pin (classloader-proof; the DorisReplayPocTest property pattern). */
    private fun <T> withFlag(value: Boolean, block: () -> T): T {
        System.setProperty(DorisCatalogs.PROPERTY, value.toString())
        try {
            return block()
        } finally {
            System.clearProperty(DorisCatalogs.PROPERTY)
        }
    }

    fun testFlagDefaultsOnSince030() {
        // M10: catalogs ship on by default; only the explicit escape hatch disables.
        assertTrue("unset must mean ENABLED (0.3.0 default)", DorisCatalogs.isEnabledValue(null))
        assertTrue(DorisCatalogs.isEnabledValue("true"))
        assertTrue("garbage values fail ON (never silently disable)", DorisCatalogs.isEnabledValue("yes"))
        assertFalse("the documented escape hatch", DorisCatalogs.isEnabledValue("false"))
        assertFalse("case-insensitive escape hatch", DorisCatalogs.isEnabledValue("FALSE"))
        // The test JVM sets no property, so the live value must be the default: enabled.
        assertTrue("live default must be enabled", DorisCatalogs.enabled)
    }

    fun testFlagOffModelFacadeIsUnchangedMysql() = withFlag(false) {
        val facade = DorisModelFacade(DorisDbms.DORIS)
        assertSame(
            "Flag-off getMetaModel() must be the shipped single-database MySQL model, unchanged.",
            MysqlMetaModel.MODEL,
            facade.metaModel,
        )
        assertTrue(
            "Flag-off getModelHelper() must be the shipped MysqlBaseModelHelper.",
            facade.modelHelper is MysqlBaseModelHelper,
        )
    }

    fun testFlagOnModelFacadeIsMsFamily() = withFlag(true) {
        val facade = DorisModelFacade(DorisDbms.DORIS)
        assertSame(MsMetaModel.MODEL, facade.metaModel)
        assertTrue(facade.modelHelper is DorisCatalogModelHelper)
    }

    fun testFlagOffIntrospectorFactoryMatchesMysqlCapabilities() = withFlag(false) {
        val doris = DorisIntrospector.DorisIntrospectorFactory(DorisDbms.DORIS)
        val mysql = MysqlBaseIntrospector.Factory(DorisDbms.DORIS)
        // Flag-off the Doris factory is a pass-through to the MySQL factory: same multilevel answer
        // (single-database, i.e. false) and same incremental answer.
        assertEquals(mysql.supportsMultilevelIntrospection, doris.supportsMultilevelIntrospection)
        assertEquals(mysql.isIncremental(), doris.isIncremental())
    }

    fun testChosenMultiDatabaseModelExposesCatalogLevel() {
        // The productionization target: SQL Server's public multi-database meta-model. Unlike the
        // MySQL model (regression-guarded below), its ROOT owns a DATABASE level = Doris catalogs.
        val ms = MsMetaModel.MODEL
        val rootChildKinds = ms.getChildKinds(ObjectKind.ROOT).toList()
        assertTrue(
            "Chosen flag-on model must expose DATABASE under ROOT. childKinds(ROOT)=$rootChildKinds",
            rootChildKinds.contains(ObjectKind.DATABASE),
        )
        val dbChildKinds = ms.getChildKinds(ObjectKind.DATABASE).toList()
        assertTrue(
            "DATABASE (catalog) must contain SCHEMA. childKinds(DATABASE)=$dbChildKinds",
            dbChildKinds.contains(ObjectKind.SCHEMA),
        )

        // Contrast: the flag-off MySQL model collapses the database level (root cause of the whole bug).
        val mysqlRootKinds = MysqlMetaModel.MODEL.getChildKinds(ObjectKind.ROOT).toList()
        assertFalse(
            "Regression guard: MySQL model must remain single-database. childKinds(ROOT)=$mysqlRootKinds",
            mysqlRootKinds.contains(ObjectKind.DATABASE),
        )
    }
}
