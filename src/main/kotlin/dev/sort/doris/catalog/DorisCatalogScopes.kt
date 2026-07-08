package dev.sort.doris.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.SmartPredicate
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternNode
import com.intellij.database.util.TreePatternUtils
import com.intellij.sql.dialects.SqlImportUtil

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

    /**
     * Flag-ON **SQL-editor import pattern** (Gate 1 / M3): every Doris catalog importable at the
     * DATABASE level of the given data sources — `dataSources(names) -> DATABASE(wildcard)`, no
     * schema children.
     *
     * ## Why (the head-segment gate, bytecode findings)
     *
     * Resolution of a qualified chain (`extcat.somedb.sometable`) resolves the **head** segment
     * through the unqualified machinery: `SqlFileImpl.processDeclarationsImpl` feeds data-source
     * namespaces filtered by `importedCondition`, whose lambda accepts a DATABASE/SCHEMA node only
     * if `SqlDialectImplUtilCore.checkImports(importState, ...)` passes (bypassed only by
     * completion's include-all mode, and even then only for nodes with children). The import state
     * is seeded by the SQL dialect's `getBaseImports` — and the inherited MySQL implementation can
     * only express schema-level imports, so a catalog (DATABASE) node **never** passes and the
     * head segment never resolves. Once the head resolves, the rest of the chain is walked via
     * `SqlImplUtil.processQualifierImpl` -> `DasObject.getDasChildren(kind)` — **no import gate** —
     * so catalog-level import coverage is sufficient for the whole 3-part chain.
     *
     * The wildcard DATABASE node deliberately carries **no schema group**: catalogs become
     * resolvable/completable as qualifiers without auto-importing every catalog's schemas into the
     * unqualified scope (which would flood completion and resolution with every external table).
     */
    fun allCatalogsImportPattern(dataSourceNames: Array<ObjectName>): TreePattern {
        val anyCatalog = TreePatternNode(TreePatternNode.NegativeNaming.WILDCARD, TreePatternNode.NO_GROUPS)
        val catalogGroup = TreePatternNode.Group(ObjectKind.DATABASE, arrayOf(anyCatalog))
        return TreePattern(SqlImportUtil.createDataSources(dataSourceNames, catalogGroup))
    }

    // ---- Scope interpretation: explicit selection vs. enumerate-only default (M8) ------------------

    /**
     * How a catalog stands relative to the data source's introspection scope (M8). Ordinal order is
     * specificity — when several pattern nodes match one catalog, the most specific wins.
     */
    enum class CatalogScopeClass {
        /** No pattern node names or matches the catalog. */
        NOT_IN_SCOPE,

        /**
         * Matched only by the M2 default's enumerate-only node — a *negative* naming **with
         * exception names** (`NegativeNaming(all(), [internal])`), a shape **no schemas-pane
         * gesture produces** (the pane writes positive names for real nodes and the bare
         * [TreePatternNode.NegativeNaming.WILDCARD] for its "*" pseudo-entries — verified in
         * `DbNamespacesTree.createNaming` bytecode). Visible in the tree, not deep-introspected.
         */
        ENUMERATED_DEFAULT,

        /**
         * Explicitly selected — a positive-named node (a catalog tick in the schemas pane whose
         * databases were not loaded serializes as exactly this: named node, **no** schema children;
         * `DbNamespacesTree.build` includes checked nodes only) or the pane's bare "all" wildcard —
         * but with no schema children. **Platform `matches()` semantics would introspect nothing
         * under it** (a schema only matches through a SCHEMA group); the M8 fix expands this to
         * all-schemas, mirroring what ticking a parent means everywhere else in DataGrip.
         */
        EXPLICIT_DEEP,

        /** Explicitly selected with its own SCHEMA group — the pattern already says which schemas. */
        EXPLICIT_WITH_SCHEMAS,
    }

    /** Classifies [catalogName] against [scope]'s DATABASE group (most specific match wins). */
    fun classifyCatalog(scope: TreePattern, catalogName: String): CatalogScopeClass {
        val group = scope.root?.getGroup(ObjectKind.DATABASE) ?: return CatalogScopeClass.NOT_IN_SCOPE
        val name = ObjectName.plain(catalogName)
        var result = CatalogScopeClass.NOT_IN_SCOPE
        for (node in group.children.orEmpty()) {
            if (node == null || !node.naming.matches(name, com.intellij.database.util.Casing.EXACT)) continue
            val cls = when {
                node.getGroup(ObjectKind.SCHEMA) != null -> CatalogScopeClass.EXPLICIT_WITH_SCHEMAS
                isExplicitSelection(node.naming) -> CatalogScopeClass.EXPLICIT_DEEP
                else -> CatalogScopeClass.ENUMERATED_DEFAULT
            }
            if (cls.ordinal > result.ordinal) result = cls
        }
        return result
    }

    /**
     * M8 fix: rewrites [scope] so every **explicitly selected** catalog node that has no SCHEMA
     * children gains a wildcard SCHEMA group — "ticking a catalog means everything under it", the
     * user-visible contract of the schemas pane. Nodes left untouched:
     *
     * - explicit nodes that already carry a SCHEMA group (the pattern says which schemas);
     * - the M2 default's enumerate-only negative-with-exceptions node (so genuinely unselected
     *   external catalogs stay visible-but-shallow — the default is a **fixed point** of this
     *   function, asserted by tests);
     * - non-DATABASE groups and everything else in the pattern.
     *
     * Returns the same instance when nothing needed expansion (cheap identity check for callers).
     */
    fun expandExplicitCatalogSelections(scope: TreePattern): TreePattern {
        val root = scope.root ?: return scope
        val dbGroup = root.getGroup(ObjectKind.DATABASE) ?: return scope
        var changed = false
        val newNodes = dbGroup.children.orEmpty().map { node ->
            if (node != null &&
                node.getGroup(ObjectKind.SCHEMA) == null &&
                isExplicitSelection(node.naming)
            ) {
                changed = true
                val allSchemas = TreePatternNode(TreePatternNode.NegativeNaming.WILDCARD, TreePatternNode.NO_GROUPS)
                val schemaGroup = TreePatternNode.Group(ObjectKind.SCHEMA, arrayOf(allSchemas))
                TreePatternNode(node.naming, node.groups.orEmpty().filterNotNull().toTypedArray() + schemaGroup)
            } else {
                node
            }
        }
        if (!changed) return scope
        val newGroups = root.groups.orEmpty().map { g ->
            if (g != null && g.kind == ObjectKind.DATABASE) {
                TreePatternNode.Group(ObjectKind.DATABASE, newNodes.filterNotNull().toTypedArray())
            } else {
                g
            }
        }.filterNotNull().toTypedArray()
        return TreePattern(TreePatternNode(root.naming, newGroups))
    }

    /**
     * A naming that only a deliberate selection produces: positive names (`DbNamespacesTree.build`
     * serializes checked real nodes as `PositiveNaming(name)`) or the pane's bare "all" wildcard
     * (`NegativeNaming` with an **empty** exception list). The M2 default's enumerate-only node is
     * `NegativeNaming` **with** exception names and is deliberately excluded.
     */
    private fun isExplicitSelection(naming: TreePatternNode.BaseNaming): Boolean {
        if (naming is TreePatternNode.NegativeNaming) {
            val names = naming.names
            return names == null || names.isEmpty()
        }
        return true // PositiveNaming (named tick or the '@' current pseudo-entry)
    }
}
