package dev.sort.doris.catalog

import com.intellij.database.Dbms
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ModelTextStorage
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.model.basic.BasicSourceAware
import com.intellij.database.model.properties.CompositeText
import com.intellij.database.util.ObjectNamePart
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The introspector's model is additive by default (`createOrGet` only ever adds), so a table/view
 * recreated with a different shape used to leave the old columns behind — `create view foo(abc)`,
 * then `create view foo(def)`, showed `abc + def` in completion (reported staleness). [attachColumns]
 * now brackets the column family with the platform sync-pending sweep to prune the vanished ones.
 *
 * These drive the REAL production [attachColumns] against a REAL SQL Server column family (the exact
 * `MsRoot` classes [DorisIntrospector] mutates flag-on, built via [ModelFactory] as in
 * [DorisModelWriteTest]) — no faking — so the prune is verified end to end, not just asserted about.
 */
class DorisIntrospectorColumnSyncTest : BasePlatformTestCase() {

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

    private fun col(name: String, pos: Long, type: String = "int") =
        DorisCatalogQueries.ColumnRow().apply {
            TABLE_NAME = "t"
            COLUMN_NAME = name
            DATA_TYPE = type
            ORDINAL_POSITION = pos
        }

    private fun columnFamilyOf(model: BasicModModel) =
        (model.root as MsRoot).databases.createOrGet("internal")
            .schemas.createOrGet("acme_db").tables.createOrGet("t").columns

    private fun columnNames(model: BasicModModel): List<String> =
        columnFamilyOf(model).map { it.name }

    /** The reported repro: recreate with a different shape → old columns pruned, not merged. */
    fun testRecreatedShapePrunesOldColumns() {
        val model = newModel()
        val columns = columnFamilyOf(model)

        DorisModelWrite.write(model) { attachColumns(columns, listOf(col("abc", 1), col("xyz", 2))) }
        assertEquals(listOf("abc", "xyz"), columnNames(model))

        DorisModelWrite.write(model) { attachColumns(columns, listOf(col("def", 1))) }
        assertEquals("stale columns must be pruned, not merged", listOf("def"), columnNames(model))
        assertNull("old column 'abc' must be gone", columns.get("abc"))
    }

    /** Re-attaching the SAME shape is a no-op set-wise (idempotent), not a wipe-and-readd churn. */
    fun testSameShapeIsIdempotent() {
        val model = newModel()
        val columns = columnFamilyOf(model)
        val rows = listOf(col("abc", 1), col("xyz", 2))
        DorisModelWrite.write(model) { attachColumns(columns, rows) }
        DorisModelWrite.write(model) { attachColumns(columns, rows) }
        assertEquals(listOf("abc", "xyz"), columnNames(model))
    }

    /** A null column result (ambiguous/incomplete fetch) must leave existing columns untouched. */
    fun testNullRowsLeavesExistingColumnsUntouched() {
        val model = newModel()
        val columns = columnFamilyOf(model)
        DorisModelWrite.write(model) { attachColumns(columns, listOf(col("abc", 1))) }
        DorisModelWrite.write(model) { attachColumns(columns, null) }
        assertEquals("null rows must not wipe a live table's columns", listOf("abc"), columnNames(model))
    }
}
