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

    /**
     * COMPAT-262.md: the skeleton is now constructed through [DorisMetaCompat] because the
     * `BasicMetaModel`/`BasicMetaObject` constructor descriptors changed between platform 261 and
     * 262 (`com.intellij.util.Function` -> `java.util.function.Function` in the factory slot).
     * This test proves the reflective resolution finds the constructors on the CURRENT platform
     * (the 261 path in CI today; the same selection logic — arity + leading param types — matches
     * the 262 descriptors) and that objects built through the compat layer are real, walkable
     * platform meta objects.
     */
    fun testMetaCompatConstructsRealMetaObjectsOnCurrentPlatform() {
        // Direct compat-layer construction: a one-node BasicMetaObject and a model around it.
        val column = DorisMetaCompat.newMetaObject(
            ObjectKind.COLUMN,
            com.intellij.database.model.basic.BasicModTableColumn::class.java,
            com.intellij.util.Function { error("dataFactory must not be invoked by construction") },
            java.util.function.BiConsumer { _, _ -> },
            arrayOf(),
            arrayOf(),
            arrayOf(),
        )
        assertEquals(ObjectKind.COLUMN, column.kind)

        val table = DorisMetaCompat.newMetaObject(
            ObjectKind.TABLE,
            com.intellij.database.model.basic.BasicModTable::class.java,
            com.intellij.util.Function { error("dataFactory must not be invoked by construction") },
            java.util.function.BiConsumer { _, _ -> },
            arrayOf(),
            arrayOf(),
            arrayOf(column),
        )
        val model = DorisMetaCompat.newMetaModel(
            DorisDbms.DORIS,
            table,
            com.intellij.database.model.basic.BasicModModel::class.java,
            com.intellij.util.Function { error("modelFactory must not be invoked by construction") },
        )
        // The ctor indexed the kind graph: the compat-built objects are genuine platform meta objects.
        assertTrue(
            "Compat-constructed BasicMetaModel must expose the TABLE -> COLUMN kind edge.",
            model.getChildKinds(ObjectKind.TABLE).toList().contains(ObjectKind.COLUMN),
        )

        // And the production path (buildMultiDatabaseSkeleton) still routes through the same layer:
        // resolving + invoking twice must keep yielding the full multi-database shape (cached ctors).
        val skeleton = DorisMetaModel.buildMultiDatabaseSkeleton(DorisDbms.DORIS)
        assertTrue(skeleton.getChildKinds(ObjectKind.ROOT).toList().contains(ObjectKind.DATABASE))
    }
}
