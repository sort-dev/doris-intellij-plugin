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
 * Gate 1 / Milestone 1 dual-mode wiring guarantees, asserted with the flag **OFF** (its default in
 * the test JVM):
 *
 *  1. Flag-off, [DorisModelFacade] returns the *exact same* MySQL model + helper the plugin has
 *     always shipped — the bit-for-bit-unchanged requirement.
 *  2. The `<introspector dbms="DORIS">` factory, flag-off, advertises the same capabilities as the
 *     stock MySQL introspector factory (it transparently delegates to it).
 *  3. The flag-ON model choice ([MsMetaModel.MODEL], SQL Server family) genuinely exposes the
 *     DATABASE (catalog) level that the MySQL model collapses — the whole point of path (a).
 */
class DorisCatalogWiringTest : BasePlatformTestCase() {

    fun testFlagIsOffByDefault() {
        assertFalse(
            "The experimental catalog model must be opt-in; default off keeps shipped behaviour.",
            DorisCatalogs.enabled,
        )
    }

    fun testFlagOffModelFacadeIsUnchangedMysql() {
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

    fun testFlagOffIntrospectorFactoryMatchesMysqlCapabilities() {
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
