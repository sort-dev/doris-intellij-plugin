package dev.sort.doris.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlReferenceExpression

/**
 * Suppresses DataGrip's semantic false-positives on Doris built-ins in Doris files:
 *  - "Unable to resolve object type 'X'" — Doris types (JSON, VARIANT, BITMAP, HLL, LARGEINT,
 *    AGG_STATE, ARRAY/MAP/STRUCT, ...) that the SQL92 substrate parses as unknown object types.
 *  - "Unknown database function 'X'" — Doris built-in functions. SqlResolveInspection resolves
 *    function calls against the introspected data-source model (ObjectKind.ROUTINE via
 *    processNameIndex), NOT the dialect's getSupportedFunctions() registry, so Doris built-ins are
 *    never found there and can't be registered into it.
 *
 * Both are safe to drop: nothing authoritatively validates these anyway (fe-sql-parser is
 * syntax-only; the DB model doesn't expose Doris built-ins). Unresolved *tables/columns* still
 * highlight normally, and fe-sql-parser ([DorisErrorAnnotator]) still catches real syntax errors.
 *
 * ## Table-valued functions (narrowly scoped "Unable to resolve column" suppression)
 * Two TVF-only contexts additionally drop unresolved-COLUMN errors ([isTvfFalsePositive]):
 *  1. Inside the ARGUMENT LIST of a registered TVF call: Doris property bags are written
 *     `"key"="value"`, and the MySQL grammar reads the double-quoted operands as column
 *     references — every documented-style call (`tasks("type"="mv")`) lit up red.
 *  2. Unresolved columns in a query whose FROM contains an OPEN-relation TVF (`s3`, `hdfs`, ...,
 *     [DorisTableFunctions.Schema.Open]): their real output columns are unknowable without
 *     reading external data (which we never do), so any column reference must be accepted
 *     silently — no fabricated columns, no red (research doc Tier B degradation).
 * TVFs with static schemas keep NORMAL column validation — their columns resolve for real via
 * [DorisTypeSystem], and a genuinely wrong column should stay red.
 */
class DorisHighlightInfoFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null || !file.language.isKindOf(DorisSqlDialect.INSTANCE)) return true
        val description = highlightInfo.description ?: return true
        if (SUPPRESSED_PREFIXES.any { description.contains(it) }) return false
        if (description.contains(UNRESOLVED_COLUMN) && isTvfFalsePositive(file, highlightInfo)) return false
        // M9 (flag-ON only): references into enumerated-but-not-introspected catalogs are
        // OUT-OF-SCOPE, not wrong — suppress instead of red-flooding. Nonexistent names under
        // INTROSPECTED namespaces (incl. internal) keep their error.
        if (dev.sort.doris.DorisCatalogs.enabled &&
            description.contains(UNRESOLVED_PREFIX) &&
            isOutOfScopeReference(file, highlightInfo)
        ) {
            return false
        }
        return true
    }

    /**
     * OUT-OF-SCOPE detection (M9, the degrade half of the "Introspect this?" design;
     * classification decision table lives in [dev.sort.doris.catalog.DorisOutOfScope]):
     *
     *  1. The failing reference is QUALIFIED and its qualifier resolves to a das namespace that is
     *     enumerated-but-childless (an external catalog, or a database introspection never
     *     visited) — the segment cannot possibly resolve until the user opts the namespace in.
     *  2. The failing reference is a COLUMN in a query whose FROM contains a table reference that
     *     is itself out-of-scope by rule 1 — without the table's columns, every column reference
     *     is unresolvable; keeping them red is pure noise (mirror of the TVF open-relation rule).
     */
    private fun isOutOfScopeReference(file: PsiFile, info: HighlightInfo): Boolean {
        val element = file.findElementAt(info.startOffset) ?: return false
        val ref = PsiTreeUtil.getParentOfType(element, SqlReferenceExpression::class.java, false)

        // (1) qualified segment whose parent path lands on a childless external namespace
        if (ref != null && isQualifierOutOfScope(ref)) return true

        // (2) column inside a query whose FROM has an out-of-scope table reference
        var query: SqlQueryExpression? = PsiTreeUtil.getParentOfType(element, SqlQueryExpression::class.java)
        while (query != null) {
            val from: PsiElement? = query.tableExpression
            if (from != null &&
                PsiTreeUtil.findChildrenOfType(from, SqlReferenceExpression::class.java)
                    .any { isQualifierOutOfScope(it) }
            ) {
                return true
            }
            query = PsiTreeUtil.getParentOfType(query, SqlQueryExpression::class.java)
        }
        return false
    }

    /** True when [ref]'s immediate qualifier resolves to an enumerated-but-childless namespace. */
    private fun isQualifierOutOfScope(ref: SqlReferenceExpression): Boolean {
        val qualifier = ref.qualifierExpression as? SqlReferenceExpression ?: return false
        val resolved = qualifier.reference?.resolve() ?: return false
        val das = resolved as? com.intellij.database.model.DasObject ?: return false
        return dev.sort.doris.catalog.DorisOutOfScope.classify(das) ==
            dev.sort.doris.catalog.DorisOutOfScope.Classification.OUT_OF_SCOPE
    }

    private fun isTvfFalsePositive(file: PsiFile, info: HighlightInfo): Boolean {
        val element = file.findElementAt(info.startOffset) ?: return false

        // (1) property-bag args of any registered TVF call
        var call: SqlFunctionCallExpression? =
            PsiTreeUtil.getParentOfType(element, SqlFunctionCallExpression::class.java)
        while (call != null) {
            val inArgs = call.parameterList?.textRange?.contains(info.startOffset) == true
            if (inArgs && DorisTableFunctions.byName(call.nameElement?.name) != null) return true
            call = PsiTreeUtil.getParentOfType(call, SqlFunctionCallExpression::class.java)
        }

        // (2) output columns of an open-relation TVF anywhere in the enclosing queries' FROMs
        var query: SqlQueryExpression? = PsiTreeUtil.getParentOfType(element, SqlQueryExpression::class.java)
        while (query != null) {
            val from: PsiElement? = query.tableExpression
            if (from != null &&
                PsiTreeUtil.findChildrenOfType(from, SqlFunctionCallExpression::class.java).any {
                    DorisTableFunctions.byName(it.nameElement?.name)?.schema is DorisTableFunctions.Schema.Open
                }
            ) return true
            query = PsiTreeUtil.getParentOfType(query, SqlQueryExpression::class.java)
        }
        return false
    }

    private companion object {
        private const val UNRESOLVED_COLUMN = "Unable to resolve column"
        private const val UNRESOLVED_PREFIX = "Unable to resolve"

        // Match on the stable leading phrase of each message (the '{0}' quoted name varies).
        private val SUPPRESSED_PREFIXES = listOf(
            "Unable to resolve object type", // Doris types unknown to the SQL92 substrate
            "Unknown database function",     // Doris built-ins absent from the introspected model
        )
    }
}
