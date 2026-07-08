package dev.sort.doris.catalog

import com.intellij.database.Dbms
import com.intellij.database.data.types.PredicatesHelper
import com.intellij.database.datagrid.HookUpHelper
import com.intellij.database.dialects.mssql.MsObjectBuilder
import com.intellij.database.dialects.mssql.MsPredicatesHelper
import com.intellij.database.dialects.mssql.generator.MsScriptGenerator
import com.intellij.database.dialects.mysqlbase.MysqlBaseHookUpHelper
import com.intellij.database.dialects.mysqlbase.MysqlBaseObjectBuilder
import com.intellij.database.dialects.mysqlbase.MysqlBasePredicatesHelper
import com.intellij.database.dialects.mysqlbase.generator.MysqlBaseScriptGenerator
import com.intellij.database.model.SqlObjectBuilder
import com.intellij.database.script.generator.ScriptGenerator
import dev.sort.doris.DorisCatalogs

/*
 * Dual-mode editor-helper overrides for `dbms="DORIS"`.
 *
 * Gate 0 found exactly five inherited MySQL extension points that hard-cast model nodes to
 * `MysqlBase*` types (the "F7 minefield"): the introspector plus four editor helpers —
 * `sqlObjectBuilder`, `scriptGenerator`, `hookUpHelper`, `predicatesHelper`. Under the flag-ON
 * `Ms*` model those MySQL casts would `ClassCastException`, so each helper must be overridden.
 *
 * ## The dual-mode hard requirement
 *
 * Extension-point beans in `plugin.xml` are static XML: a `dbms="DORIS"` registration is live
 * whether the flag is on or off. So each override MUST reproduce today's behaviour flag-OFF (when
 * the model is MySQL) *and* be `Ms*`-safe flag-ON. We satisfy this with **Kotlin interface
 * delegation whose delegate is chosen once, at construction, from [DorisCatalogs.enabled]**:
 *
 *   - flag OFF -> delegate is the exact `MysqlBase*` implementation the platform's
 *     `extensionFallback DORIS -> MYSQL` would have produced (byte-for-byte today's behaviour;
 *     the injected `Dbms` we pass through is the same `DORIS` value the fallback passes — verified
 *     against `DbmsExtension.copyFromFallback`, which forwards the *original* dbms to the bean).
 *   - flag ON  -> delegate is the SQL Server (`Ms*`) implementation, matching the flag-ON `Ms*`
 *     model. (`MsObjectBuilder`/`MsScriptGenerator`/`MsPredicatesHelper` are shipped, public and
 *     instantiable.)
 *
 * Because [DorisCatalogs.enabled] is read once per session, the delegate is fixed for the JVM's
 * lifetime — there is no per-call branching and no way for the two modes to interleave.
 *
 * `hookUpHelper` is special: SQL Server ships no `MsHookUpHelper`, and `MysqlBaseHookUpHelper`'s only
 * model cast is **guarded** by an `instanceof MysqlBaseLikeColumn` (verified in bytecode), so it is
 * already `Ms*`-safe (it simply returns default attributes for a non-MySQL column) and additionally
 * preserves the MySQL-flavoured filter/sort language that suits Doris's MySQL protocol. We therefore
 * delegate it to `MysqlBaseHookUpHelper` in both modes.
 */

/** `sqlObjectBuilder dbms="DORIS"` — PSI->model in the SQL editor. Unguarded MySQL node casts, so must route. */
class DorisObjectBuilder :
    SqlObjectBuilder by (if (DorisCatalogs.enabled) MsObjectBuilder() else MysqlBaseObjectBuilder())

/** `scriptGenerator dbms="DORIS"` — DDL/script export. */
class DorisScriptGenerator(dbms: Dbms) :
    ScriptGenerator by (
        if (DorisCatalogs.enabled) MsScriptGenerator(dbms) else MysqlBaseScriptGenerator(dbms)
    )

/** `predicatesHelper dbms="DORIS"` — data-grid filter predicate producers. */
class DorisPredicatesHelper(dbms: Dbms) :
    PredicatesHelper by (
        if (DorisCatalogs.enabled) MsPredicatesHelper(dbms) else MysqlBasePredicatesHelper(dbms)
    )

/**
 * `hookUpHelper dbms="DORIS"` — data-grid filter/sort language + column attributes. Delegates to
 * `MysqlBaseHookUpHelper` in both modes: its lone model cast is guarded by `instanceof`, so it is
 * safe under the flag-ON `Ms*` model, and its MySQL filter/sort language is correct for Doris.
 */
class DorisHookUpHelper(dbms: Dbms) :
    HookUpHelper by MysqlBaseHookUpHelper(dbms)
