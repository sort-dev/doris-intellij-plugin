package dev.sort.doris.sql

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Doris reads a time zone after the time of a TIMESTAMP literal
 * (`TIMESTAMP '2026-01-01 00:00:00 UTC'`, `+08:00`, `Asia/Shanghai`); the platform's injected date-time
 * language does not, and reports the zone as unexpected characters.
 */
class DorisDatetimeZoneErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val file = element.containingFile ?: return true
        if (file.language.id != "SQLDateTime") return true
        val host = InjectedLanguageManager.getInstance(element.project).getInjectionHost(file) ?: return true
        if (!host.containingFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return true
        val keyword = PsiTreeUtil.prevVisibleLeaf(host)?.text?.uppercase()
        if (keyword != "TIMESTAMP") return true
        return !ZONED.matches(host.text)
    }

    private companion object {
        val ZONED = Regex("""'\d{4}-\d{1,2}-\d{1,2}[ T]\d{1,2}:\d{2}(:\d{2}(\.\d+)?)?\s*(UTC|GMT|Z|[A-Za-z]+(/[A-Za-z_]+)+|[+-]\d{1,2}(:?\d{2})?)'""")
    }
}
