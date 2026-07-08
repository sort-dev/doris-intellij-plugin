package dev.sort.doris.catalog

import com.intellij.database.model.ModelHelper
import com.intellij.database.model.ObjectKind

/**
 * Flag-ON ModelHelper: renames the DATABASE object kind to **"catalog"** so the database tree and
 * the schemas pane label Doris's top level correctly (Gate 1 / M2, problem 2).
 *
 * ## Mechanism
 *
 * Kind display names are produced by `ModelHelper.getName(kind, plural)`, which consults the
 * per-dbms [getCustomName] hook first and falls back to the static `ObjectKind` bundle name.
 * Consumers verified in bytecode: `DvTreeModelLayer` (database-explorer family nodes),
 * `DbPresentationCore` (object presentation), `DbNamespacesTree` (the schemas pane). This is the
 * exact mechanism Cassandra uses to rename SCHEMA to "keyspace" (`CassModelHelper.getCustomName`),
 * so it is per-dbms by design, cheap, and needs no model contortion. The dbms's ModelHelper is
 * obtained through `ModelFacade.forDbms(...).getModelHelper()` — which is
 * [dev.sort.doris.DorisModelFacade], flag-routed.
 *
 * ## Why extend [ModelHelper] and not `MsModelHelper`
 *
 * `MsModelHelper` is `final`. Its overrides beyond the base are SQL-Server-specific niceties
 * (LOGIN/ROLE custom names, MSSQL grant controller, creation-template examples) for object kinds
 * the Doris introspector never populates in M1/M2 — the generic base behaviour is correct for
 * everything the Doris tree actually shows. Known cosmetic gap: fixed platform bundle strings that
 * hardcode the word "database(s)" outside the kind-name mechanism are not affected by this hook.
 *
 * Registered only via the flag-ON branch of `DorisModelFacade.getModelHelper()`; flag-OFF keeps
 * `MysqlBaseModelHelper` untouched, so shipped labels are unchanged there.
 */
class DorisCatalogModelHelper : ModelHelper() {

    override fun getCustomName(kind: ObjectKind, plural: Boolean): String? {
        if (kind == ObjectKind.DATABASE) {
            return if (plural) "catalogs" else "catalog"
        }
        return super.getCustomName(kind, plural)
    }
}
