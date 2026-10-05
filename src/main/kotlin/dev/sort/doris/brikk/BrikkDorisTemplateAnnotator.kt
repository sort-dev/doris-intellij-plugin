package dev.sort.doris.brikk

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkDorisTemplateAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val entry = element as? KtStringTemplateEntryWithExpression ?: return
        val host = entry.parent as? KtStringTemplateExpression ?: return
        if (!BrikkDorisTemplate.isRecognized(host)) return
        val result = BrikkDorisTemplate.classify(entry.expression)
        val problem = result.problem ?: return
        holder.newAnnotation(HighlightSeverity.ERROR, "Brikk-house: $problem").range(entry).create()
    }
}
