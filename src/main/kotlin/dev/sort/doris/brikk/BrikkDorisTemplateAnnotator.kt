package dev.sort.doris.brikk

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.openapi.util.TextRange
import dev.brikk.house.intellij.ContextResult
import dev.brikk.house.intellij.EntryRole
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkDorisTemplateAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val host = element as? KtStringTemplateExpression ?: return
        val result = BrikkDorisTemplate.inspect(host) as? ContextResult.Available ?: return
        for (entry in result.context.entries) {
            // Pending resolution is not a compiler refusal or an authored error.
            if (entry.role == EntryRole.PENDING) continue
            val problem = entry.problem ?: continue
            val range = TextRange(entry.range.start, entry.range.end).shiftRight(host.textRange.startOffset)
            holder.newAnnotation(HighlightSeverity.ERROR, "Brikk-house: $problem").range(range).create()
        }
    }
}
