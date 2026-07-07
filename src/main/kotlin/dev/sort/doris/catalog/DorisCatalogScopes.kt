package dev.sort.doris.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.SmartPredicate
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternNode
import com.intellij.database.util.TreePatternUtils

/**
 * Default introspection scopes for Doris data sources (Gate 1 / M2).
 *
 * ## The mechanism (bytecode findings, DB-261)
 *
 * The default scope of a **new** data source is *introspector-keyed*, not dbms- or driver-keyed:
 * on first introspection, `DatabaseIntrospectionSession.performTasksInTheConnectedSession` sees the
 * data source's `introspectionScope` is **empty**, runs `introspectNamespaces()`, then
 * `updateDataSourceScope()` copies `DBIntrospector.getDefaultScope()` into
 * `LocalDataSource.setIntrospectionScope(...)`. It runs **only when the scope is empty**, so a
 * user's explicit later selections are never clobbered — exactly the hook M2 needs.
 *
 * The shipped defaults are `BaseSingleDatabaseIntrospector` -> `SINGLE_DB_SCOPE` (schema named `@`)
 * and `BaseMultiDatabaseIntrospector` -> `MULTI_DB_SCOPE` (`@` database -> `@` schema), where `@`
 * (`DataSourceSchemaMapping.CURRENT_NAMESPACE_NAME`) matches a namespace whose
 * `BasicNamespace.isCurrent()` is true. That is why a fresh Doris data source looks empty (GitHub
 * issue #5): if no namespace ever gets marked current (Doris connections often report no current
 * database; flag-ON our M1 lister marked an arbitrary first catalog), `@` matches **nothing** and
 * the tree shows nothing until the user selects scopes by hand. The fix is to return *explicit*
 * Doris-appropriate patterns instead of relying solely on `@`.
 *
 * Pattern vocabulary (all public API): [TreePatternNode.PositiveNaming] (named match),
 * [TreePatternNode.NegativeNaming] (matches everything **except** its names — with an empty name
 * list this is the platform's wildcard, [TreePatternNode.NegativeNaming.WILDCARD]), grouped per
 * [ObjectKind] level.
 */
object DorisCatalogScopes {

    /** Doris's built-in catalog, always present, always cheap to introspect. */
    const val INTERNAL_CATALOG: String = "internal"

    /**
     * Flag-ON default scope: **every catalog enumerated at the database level; only `internal`
     * deep-introspected.**
     *
     * - `internal` node: positive name match with a wildcard SCHEMA group -> all of internal's
     *   databases (and their tables/columns) introspect by default — the M1 runtime behaviour the
     *   user had to click "all schemas" to get.
     * - all-other-catalogs node: negative naming (everything except `internal`) with **no** SCHEMA
     *   group -> external catalogs are in scope as *database nodes only*: visible/expandable in the
     *   tree, but none of their schemas are selected for deep introspection. External catalogs can
     *   front huge hive metastores; the user opts in per catalog via the schemas pane.
     */
    fun multiCatalogDefaultScope(): TreePattern {
        val allSchemas = TreePatternNode(TreePatternNode.NegativeNaming.WILDCARD, TreePatternNode.NO_GROUPS)
        val internalDeep = TreePatternNode(
            TreePatternNode.PositiveNaming(ObjectName.plain(INTERNAL_CATALOG)),
            arrayOf(TreePatternNode.Group(ObjectKind.SCHEMA, arrayOf(allSchemas))),
        )
        val externalsEnumeratedOnly = TreePatternNode(
            TreePatternNode.NegativeNaming(SmartPredicate.all(), ObjectName.plain(INTERNAL_CATALOG)),
            TreePatternNode.NO_GROUPS,
        )
        return TreePattern(TreePatternNode.Group(ObjectKind.DATABASE, arrayOf(internalDeep, externalsEnumeratedOnly)))
    }

    /**
     * Flag-OFF default scope (issue #5): the platform's own single-database default ([base] =
     * `SINGLE_DB_SCOPE`, the `@` current-schema pattern — MySQL parity) **plus**, when the
     * connection actually reports a current database ([currentDatabase], from the introspector's
     * `getCurrentDatabase()` = jdba `ConnectionInfo.databaseName`), that database by name. The named
     * node keeps the default working even when nothing gets flagged `isCurrent` in the model — the
     * observed #5 failure mode. With no current database known, behaviour is unchanged (and
     * identical to a genuine MySQL data source in the same situation).
     */
    fun singleDatabaseDefaultScope(base: TreePattern, currentDatabase: String?): TreePattern {
        if (currentDatabase.isNullOrBlank()) return base
        val named = TreePattern(
            TreePatternUtils.create(ObjectName.plain(currentDatabase), ObjectKind.SCHEMA),
        )
        return TreePatternUtils.union(base, named)
    }
}
