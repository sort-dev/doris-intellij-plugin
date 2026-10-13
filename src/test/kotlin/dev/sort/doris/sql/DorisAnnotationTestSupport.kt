package dev.sort.doris.sql

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.lang.annotation.Annotation
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.lang.annotation.Annotator
import com.intellij.psi.PsiFile

internal fun dorisAnnotations(file: PsiFile): List<Annotation> {
    val annotator = DorisErrorAnnotator()
    val result = annotator.doAnnotate(annotator.collectInformation(file))
    val holder = AnnotationHolderImpl(AnnotationSession(file), false)
    holder.runAnnotatorWithContext(file, Annotator { _, annotations -> annotator.apply(file, result, annotations) })
    holder.assertAllAnnotationsCreated()
    return holder.toList()
}
