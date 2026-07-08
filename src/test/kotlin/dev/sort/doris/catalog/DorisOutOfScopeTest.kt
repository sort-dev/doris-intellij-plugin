package dev.sort.doris.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * M9 Part A offline assertions: the OUT-OF-SCOPE classification decision table that
 * [dev.sort.doris.sql.DorisHighlightInfoFilter] consults before suppressing an unresolved-reference
 * error. Only OUT_OF_SCOPE suppresses; NONEXISTENT and NOT_APPLICABLE keep the error red.
 */
class DorisOutOfScopeTest : BasePlatformTestCase() {

    fun testChildlessExternalNamespacesAreOutOfScope() {
        // Reference into an enumerated-but-never-introspected external catalog: parent resolves to
        // a childless DATABASE (catalog) node -> quiet, not red.
        assertEquals(
            DorisOutOfScope.Classification.OUT_OF_SCOPE,
            DorisOutOfScope.classify(ObjectKind.DATABASE, parentChildless = true),
        )
        // Same one level down: a database inside an external catalog that was listed but whose
        // tables were never introspected.
        assertEquals(
            DorisOutOfScope.Classification.OUT_OF_SCOPE,
            DorisOutOfScope.classify(ObjectKind.SCHEMA, parentChildless = true),
        )
    }

    fun testIntrospectedNamespacesKeepErrorsRed() {
        // internal (or any introspected catalog) has children: a name that still fails to resolve
        // under it is genuinely nonexistent -> stays red.
        assertEquals(
            DorisOutOfScope.Classification.NONEXISTENT,
            DorisOutOfScope.classify(ObjectKind.DATABASE, parentChildless = false),
        )
        assertEquals(
            DorisOutOfScope.Classification.NONEXISTENT,
            DorisOutOfScope.classify(ObjectKind.SCHEMA, parentChildless = false),
        )
    }

    fun testNonNamespaceOrUnresolvedParentsAreNotApplicable() {
        // Parent did not resolve at all (e.g. a nonexistent catalog name) -> keep red.
        assertEquals(
            DorisOutOfScope.Classification.NOT_APPLICABLE,
            DorisOutOfScope.classify(null, parentChildless = true),
        )
        assertEquals(DorisOutOfScope.Classification.NOT_APPLICABLE, DorisOutOfScope.classify(null))
        // Parent resolved to a non-namespace (table, column, ...) -> this rule does not apply.
        assertEquals(
            DorisOutOfScope.Classification.NOT_APPLICABLE,
            DorisOutOfScope.classify(ObjectKind.TABLE, parentChildless = true),
        )
        assertEquals(
            DorisOutOfScope.Classification.NOT_APPLICABLE,
            DorisOutOfScope.classify(ObjectKind.COLUMN, parentChildless = false),
        )
    }
}
