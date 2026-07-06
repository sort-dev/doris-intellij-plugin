package com.brikk.doris.sql

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement

/**
 * The DorisSQL editor is parsed by the permissive SQL92 base parser, which does not
 * understand Doris (or even MySQL) syntax such as SHOW / USE / UNIQUE KEY / DISTRIBUTED BY
 * / PARTITION BY. Left alone it paints red PsiErrorElements over all of it.
 *
 * We suppress those base-parser syntax errors for Doris files entirely. Doris-accurate
 * error reporting is layered on separately via the embedded fe-sql-parser (see PLAN.md),
 * which becomes the single source of truth for real syntax errors.
 */
class DorisHighlightErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val file = element.containingFile ?: return true
        // false => do not highlight this error element
        return !file.language.isKindOf(DorisSqlDialect.INSTANCE)
    }
}
