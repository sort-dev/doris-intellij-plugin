package dev.sort.doris.catalog

import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind

/**
 * OUT-OF-SCOPE classification for failed references (Gate 1 / M9, Part A — the degrade half of the
 * "Introspect this?" design in RESEARCH-catalog-introspection.md).
 *
 * Flag-ON, external catalogs are *enumerated but not introspected* by default (M2/M8): their model
 * nodes exist but are **childless**. A reference into one (`extcat.somedb.sometable`) fails
 * resolution at the first un-introspected level — but that failure means "not introspected yet",
 * not "does not exist", and must not red-flood the editor. The classification:
 *
 * | Parent of the failing segment resolves to | Verdict |
 * |---|---|
 * | a DATABASE (catalog) or SCHEMA node with **no** introspected children | [Classification.OUT_OF_SCOPE] — suppress the error |
 * | a DATABASE/SCHEMA node **with** children (introspected — e.g. `internal`) | [Classification.NONEXISTENT] — the name is genuinely absent, keep red |
 * | anything else (parent unresolved — e.g. a nonexistent catalog — or a non-namespace) | [Classification.NOT_APPLICABLE] — keep red |
 *
 * Known false-quiet: a genuinely *empty* introspected database is indistinguishable from a
 * never-visited one at the model level (both childless) — a bad table name under it goes quiet
 * instead of red. Preferable to the inverse (false-red on every out-of-scope reference).
 */
object DorisOutOfScope {

    enum class Classification { OUT_OF_SCOPE, NONEXISTENT, NOT_APPLICABLE }

    /** Pure decision table over the extracted facts (unit-tested). */
    fun classify(parentKind: ObjectKind?, parentChildless: Boolean): Classification {
        if (parentKind != ObjectKind.DATABASE && parentKind != ObjectKind.SCHEMA) {
            return Classification.NOT_APPLICABLE
        }
        return if (parentChildless) Classification.OUT_OF_SCOPE else Classification.NONEXISTENT
    }

    /** Classifies against a resolved parent das node (null = parent did not resolve → keep red). */
    fun classify(parent: DasObject?): Classification {
        parent ?: return Classification.NOT_APPLICABLE
        return classify(parent.kind, isChildlessNamespace(parent))
    }

    /**
     * "Never introspected" at the model level: a catalog with no databases listed, or a database
     * with no tables/views. (Introspection always lists both tables and views together, so an
     * introspected non-empty namespace has at least one child of these kinds.)
     */
    fun isChildlessNamespace(node: DasObject): Boolean {
        return when (node.kind) {
            ObjectKind.DATABASE -> node.getDasChildren(ObjectKind.SCHEMA).isEmpty
            ObjectKind.SCHEMA ->
                node.getDasChildren(ObjectKind.TABLE).isEmpty &&
                    node.getDasChildren(ObjectKind.VIEW).isEmpty
            else -> false
        }
    }
}
