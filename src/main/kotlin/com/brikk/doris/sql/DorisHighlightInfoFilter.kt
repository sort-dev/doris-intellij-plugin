package com.brikk.doris.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.psi.PsiFile

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
 */
class DorisHighlightInfoFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null || !file.language.isKindOf(DorisSqlDialect.INSTANCE)) return true
        val description = highlightInfo.description ?: return true
        return SUPPRESSED_PREFIXES.none { description.contains(it) }
    }

    private companion object {
        // Match on the stable leading phrase of each message (the '{0}' quoted name varies).
        private val SUPPRESSED_PREFIXES = listOf(
            "Unable to resolve object type", // Doris types unknown to the SQL92 substrate
            "Unknown database function",     // Doris built-ins absent from the introspected model
        )
    }
}
