package dev.sort.doris.model

import com.intellij.database.dialects.mysql.model.MysqlMetaModel
import com.intellij.database.model.ObjectKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms

/**
 * Gate 0 (RESEARCH-catalog-introspection.md) compile + construct proof for path (a): a two-level
 * (multi-database) DataGrip model is buildable by this third-party plugin from public API, and it
 * exposes the DATABASE (catalog) level that the inherited single-database MySQL model collapses.
 *
 * The test contrasts, in-process:
 *  - [MysqlMetaModel.MODEL] — what the plugin inherits today: ROOT has NO DATABASE child kind
 *    (database level collapsed onto the root), so external catalogs have nowhere to live.
 *  - [DorisMetaModel.buildMultiDatabaseSkeleton] — the Doris skeleton: ROOT -> DATABASE -> SCHEMA ->
 *    TABLE -> COLUMN, walked purely through the public [com.intellij.database.model.meta.BasicMetaModel.getChildKinds]
 *    kind graph (no live node instances required).
 */
class DorisMetaModelTest : BasePlatformTestCase() {

    fun testMysqlModelHasNoDatabaseLevel() {
        val mysql = MysqlMetaModel.MODEL
        val rootChildKinds = mysql.getChildKinds(ObjectKind.ROOT).toList()
        // The whole reason Doris catalogs are invisible: MySQL collapses the database level.
        assertFalse(
            "Regression guard: MySQL model is expected to be SINGLE-database (no DATABASE under ROOT). " +
                "childKinds(ROOT)=$rootChildKinds",
            rootChildKinds.contains(ObjectKind.DATABASE),
        )
        // MySQL binds SCHEMA directly under ROOT.
        assertTrue(
            "Expected MySQL ROOT to expose SCHEMA directly. childKinds(ROOT)=$rootChildKinds",
            rootChildKinds.contains(ObjectKind.SCHEMA),
        )
    }

    fun testDorisSkeletonIsMultiDatabase() {
        val model = DorisMetaModel.buildMultiDatabaseSkeleton(DorisDbms.DORIS)

        val rootChildren = model.getChildKinds(ObjectKind.ROOT).toList()
        assertTrue(
            "Doris skeleton ROOT must expose the DATABASE (catalog) level. childKinds(ROOT)=$rootChildren",
            rootChildren.contains(ObjectKind.DATABASE),
        )

        val dbChildren = model.getChildKinds(ObjectKind.DATABASE).toList()
        assertTrue(
            "Doris DATABASE (catalog) must contain SCHEMA. childKinds(DATABASE)=$dbChildren",
            dbChildren.contains(ObjectKind.SCHEMA),
        )

        val schemaChildren = model.getChildKinds(ObjectKind.SCHEMA).toList()
        assertTrue(
            "Doris SCHEMA must contain TABLE. childKinds(SCHEMA)=$schemaChildren",
            schemaChildren.contains(ObjectKind.TABLE),
        )

        val tableChildren = model.getChildKinds(ObjectKind.TABLE).toList()
        assertTrue(
            "Doris TABLE must contain COLUMN. childKinds(TABLE)=$tableChildren",
            tableChildren.contains(ObjectKind.COLUMN),
        )

        // The full catalog -> database -> table namespace path exists as a walkable kind chain: the
        // shape Doris external catalogs (SHOW CATALOGS) require, which the MySQL model cannot express.
        assertEquals(ObjectKind.DATABASE, DorisMetaModel.CATALOG_KIND)
        assertEquals(ObjectKind.SCHEMA, DorisMetaModel.SCHEMA_KIND)
    }
}
