package dev.sort.doris.catalog

import com.intellij.database.Dbms
import com.intellij.database.dialects.mssql.model.MsDatabase
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.dialects.mssql.model.MsSchema
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ModelTextStorage
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.model.basic.BasicSourceAware
import com.intellij.database.model.properties.CompositeText
import com.intellij.database.util.ObjectNamePart
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Companion to [DorisIntrospectorColumnSyncTest] for the other levels the introspector refreshes:
 * databases ([attachSchemas]) and tables/views ([attachTablesAndViews]). Same additive-staleness bug
 * (dropped objects lingered because the retrievers only ever `createOrGet`), same fix (platform
 * sync-pending sweep), same test approach: drive the REAL production functions against a REAL
 * `MsRoot` model built via [ModelFactory] — no faking — so a dropped prune call would fail the test.
 */
class DorisIntrospectorObjectSyncTest : BasePlatformTestCase() {

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

    private fun newModel(): BasicModModel = ModelFactory(NoopStorage()).createModel(Dbms.MSSQL)

    private fun databaseOf(model: BasicModModel): MsDatabase =
        (model.root as MsRoot).databases.createOrGet("internal")

    private fun schemaOf(model: BasicModModel): MsSchema =
        databaseOf(model).schemas.createOrGet("acme_db")

    private fun tableRow(name: String, type: String) =
        DorisCatalogQueries.TableRow().apply { TABLE_NAME = name; TABLE_TYPE = type }

    // ---- databases (attachSchemas) ---------------------------------------------------------------

    fun testDroppedDatabaseIsPruned() {
        val model = newModel()
        val database = databaseOf(model)
        DorisModelWrite.write(model) { attachSchemas(database, arrayOf("keep_db", "drop_db")) }
        assertEquals(listOf("keep_db", "drop_db"), database.schemas.map { it.name })

        // Re-introspect a catalog that now has only keep_db.
        DorisModelWrite.write(model) { attachSchemas(database, arrayOf("keep_db")) }
        assertEquals("dropped database must be pruned", listOf("keep_db"), database.schemas.map { it.name })
    }

    fun testBlankDatabaseNamesAreSkipped() {
        val model = newModel()
        val database = databaseOf(model)
        DorisModelWrite.write(model) { attachSchemas(database, arrayOf("real_db", "  ", "")) }
        assertEquals(listOf("real_db"), database.schemas.map { it.name })
    }

    // ---- tables + views (attachTablesAndViews) ---------------------------------------------------

    fun testDroppedTableAndViewArePruned() {
        val model = newModel()
        val schema = schemaOf(model)
        DorisModelWrite.write(model) {
            attachTablesAndViews(
                schema,
                listOf(tableRow("t_keep", "BASE TABLE"), tableRow("t_drop", "BASE TABLE"), tableRow("v_drop", "VIEW")),
                emptyMap(),
            )
        }
        assertEquals(listOf("t_keep", "t_drop"), schema.tables.map { it.name })
        assertEquals(listOf("v_drop"), schema.views.map { it.name })

        // Re-introspect: t_drop and v_drop are gone, a new view appeared.
        val counts = mutableListOf<TableViewCounts>()
        DorisModelWrite.write(model) {
            counts += attachTablesAndViews(
                schema,
                listOf(tableRow("t_keep", "BASE TABLE"), tableRow("v_new", "VIEW")),
                emptyMap(),
            )
        }
        assertEquals("dropped table must be pruned", listOf("t_keep"), schema.tables.map { it.name })
        assertEquals("dropped view pruned, new view added", listOf("v_new"), schema.views.map { it.name })
        assertEquals(TableViewCounts(tables = 1, views = 1), counts.single())
    }

    /** A table recreated AS a view (or vice versa) must move families, not appear in both. */
    fun testKindChangeMovesBetweenTableAndViewFamilies() {
        val model = newModel()
        val schema = schemaOf(model)
        DorisModelWrite.write(model) {
            attachTablesAndViews(schema, listOf(tableRow("foo", "BASE TABLE")), emptyMap())
        }
        assertEquals(listOf("foo"), schema.tables.map { it.name })
        assertTrue(schema.views.isEmpty())

        DorisModelWrite.write(model) {
            attachTablesAndViews(schema, listOf(tableRow("foo", "VIEW")), emptyMap())
        }
        assertTrue("old table 'foo' must be pruned when it becomes a view", schema.tables.isEmpty())
        assertEquals(listOf("foo"), schema.views.map { it.name })
    }
}
