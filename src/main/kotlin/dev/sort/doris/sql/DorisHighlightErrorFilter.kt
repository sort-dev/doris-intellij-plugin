package dev.sort.doris.sql

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement

/**
 * The DorisSQL editor is parsed on the MySQL base grammar (`MysqlDialectBase`; see
 * [DorisSqlDialect]), which handles standard SQL and MySQL but not Doris-only syntax such as
 * DISTRIBUTED BY / PROPERTIES / UNIQUE KEY table models / inverted-index DDL. Left alone it
 * paints red PsiErrorElements over all of it.
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
