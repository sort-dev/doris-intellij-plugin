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
 * 0.4.0 P1 (`Session not started`): [DorisModelWrite] must keep the introspector's family
 * mutations from ever reaching [ModelTextStorage.handleRename] — the callback that
 * `DbSrcModelStorage` answers with `assert "Session not started"` whenever an introspection flow
 * runs without a prepared write session (see [DorisModelWrite] for the platform bytecode trail).
 *
 * The model here is the REAL SQL Server family model ([MsRoot] — the same classes
 * [DorisIntrospector] mutates flag-on), built with a recording text storage in place of
 * `DbSrcModelStorage`.
 *
 * Two directions:
 *  1. the mechanism pin: BARE `createOrGet` on an identifying family notifies the storage's rename
 *     listener (if the platform ever stops doing that, this workaround is obsolete — this test
 *     will say so);
 *  2. the fix pin: the same mutations inside [DorisModelWrite.write] never touch the listener.
 */
class DorisModelWriteTest : BasePlatformTestCase() {

    private class RecordingStorage : ModelTextStorage {
        val renames = mutableListOf<String>()
        override fun handleRename(element: BasicElement, oldName: ObjectNamePart) {
            renames += "${element.kind}:'${oldName.name}'->'${element.name}'"
        }
        override fun save(element: BasicSourceAware, text: CompositeText?) = Unit
        override fun queueDelete(element: BasicElement) = Unit
        override fun load(element: BasicSourceAware): CompositeText? = null
        override fun getVersion(element: BasicElement): Long? = null
        override fun setVersion(element: BasicElement, version: Long?) = Unit
        override fun writeSession(model: BasicModModel, runnable: Runnable) = runnable.run()
        override fun flushQueues() = Unit
        override fun clear() = Unit
    }

    private fun newMsModel(storage: ModelTextStorage): BasicModModel =
        ModelFactory(storage).createModel(Dbms.MSSQL)

    /** The exact mutation shape of the introspector's retrievers: catalog -> schema -> table. */
    private fun mutateLikeTheRetrievers(model: BasicModModel) {
        val root = model.root as MsRoot
        val database = root.databases.createOrGet("internal")
        val schema = database.schemas.createOrGet("acme_test")
        schema.tables.createOrGet("acme_events")
    }

    fun testBareFamilyMutationNotifiesRenameListener() {
        val storage = RecordingStorage()
        val model = newMsModel(storage)
        mutateLikeTheRetrievers(model)
        assertTrue(
            "MECHANISM PIN: bare createOrGet on the Ms families no longer notifies the text " +
                "storage's rename listener — the DorisModelWrite workaround may be obsolete " +
                "(recorded: ${storage.renames})",
            storage.renames.isNotEmpty(),
        )
    }

    fun testWriteWrappedMutationIsSilent() {
        val storage = RecordingStorage()
        val model = newMsModel(storage)
        DorisModelWrite.write(model) { mutateLikeTheRetrievers(model) }
        assertTrue(
            "expected NO rename-listener traffic under DorisModelWrite.write " +
                "but got: ${storage.renames}",
            storage.renames.isEmpty(),
        )
        // The mutations themselves must still land.
        val root = model.root as MsRoot
        val schema = root.databases.get("internal")?.schemas?.get("acme_test")
        assertNotNull("schema created under write()", schema)
        assertNotNull("table created under write()", schema!!.tables.get("acme_events"))
    }

    fun testWriteWrappedRenameOfExistingNodeIsSilent() {
        val storage = RecordingStorage()
        val model = newMsModel(storage)
        DorisModelWrite.write(model) {
            val root = model.root as MsRoot
            root.databases.createOrGet("internal").schemas.createOrGet("acme_test")
        }
        storage.renames.clear()
        // A real rename (existing node, different name) is the strongest notify trigger.
        DorisModelWrite.write(model) {
            val root = model.root as MsRoot
            root.databases.get("internal")!!.schemas.get("acme_test")!!.name = "ACME_TEST"
        }
        assertTrue(
            "expected NO rename-listener traffic for a write()-wrapped rename " +
                "but got: ${storage.renames}",
            storage.renames.isEmpty(),
        )
    }
}
