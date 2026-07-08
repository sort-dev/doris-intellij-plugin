package dev.sort.doris.catalog

import com.intellij.database.model.BaseModel
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.model.basic.BasicModRoot

/**
 * The sanctioned model-mutation context for [DorisIntrospector]'s retrievers (0.4.0 P1 fix for the
 * live `Session not started` assertion).
 *
 * ## The bug (all claims bytecode-verified against DB-261)
 *
 * Naming a model element notifies the model's text storage: `MsImplModel$Schema.setName` →
 * `MsGeneratedModelUtil.handleRename` → `BaseModel.handleRename` →
 * `ModelTextStorage.handleRename`. When the storage is the persisted-source storage
 * (`DbSrcModelStorage`, attached to every introspection model built by
 * `DbSrcModelStorageService.createFactory`), its `handleRename` calls `getModSession()`, which
 * `LOG.assertTrue(myModSession != null, "Session not started")` — the red error the user saw.
 *
 * That write session is only open while `DatabaseIntrospectionSession` has the introspector
 * *prepared*: `prepareIntrospectorIfNeeded` → `initIntrospector` → `DbSrcModelStorage.startWriteSession`
 * (offset 80), closed again by `closeSrcSession` at the end of `introspectIt`. But not every flow
 * that reaches our retrievers prepares the introspector: `introspectTasksInDifferentSessions`
 * (offsets 56–106) calls `myIntrospector.introspect(tasks)` DIRECTLY — no
 * `connectAndDoIntrospectOperation`, no `prepareIntrospectorIfNeeded`, no write session — whenever
 * the session is not first-time and no task relates to the guessed initial database. With
 * multi-catalog Doris that is the common shape: DATABASE-level (catalog) tasks for anything other
 * than `internal` (e.g. the schemas-pane tick of an external catalog) take exactly this path, so
 * every fresh node our retrievers created fired the assert.
 *
 * ## The sanctioned pattern
 *
 * Stock retrievers never mutate families bare. `MsIntrospector$MsDatabaseRetriever.retrieveSchemas`
 * runs its queries outside and applies rows inside `inDatabase { ... }` =
 * `model.modify(database, class, modifier)` (`BaseNativeIntrospector$AbstractDatabaseRetriever.inDatabase`,
 * offset 30); `BaseIntrospector.inModel/inSchema` are the same wrapper at other levels, and the
 * platform itself wraps `DatabaseLister.applyDatabases` in `inModel` (bytecode offset 11). `modify`
 * takes the model write lock and runs the batch listeners afterwards.
 *
 * We additionally pass `fast = true` (`BaseModel.modify(element, class, /*fast*/ true, modifier)` —
 * the mode `BaseModel.modifyFast` uses): in fast mode `BaseModel.handleRename` returns before
 * touching the text storage (bytecode offsets 54–61), so the rename listener can never fire the
 * assert no matter which introspection flow invoked us. That is safe here because these retrievers
 * only CREATE nodes or re-apply identical names — there is never an existing stored source text
 * keyed by an old name that the storage would need to migrate (the queued-rename bookkeeping fast
 * mode skips exists for real DDL renames, not for introspection creation).
 *
 * Non-[BaseModel] models (offline unit-test models with no storage attached) run the mutation
 * directly — there is no rename listener to protect and no platform lock to take.
 */
internal object DorisModelWrite {

    /** Runs [mutate] under the model write lock in fast mode; see class doc. */
    fun write(model: BasicModModel?, mutate: () -> Unit) {
        val base = model as? BaseModel
        val root = base?.root as? BasicModRoot
        if (base == null || root == null) {
            mutate()
            return
        }
        base.modify(root, BasicModRoot::class.java, /* fast = */ true) { mutate() }
    }
}
