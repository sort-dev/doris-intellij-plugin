package dev.sort.doris.sql

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.pipe.PipeStageCompletionContributor
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.sort.doris.pipes.DorisPipeDialect

class DorisPipeStageCompletionContributor : PipeStageCompletionContributor(DorisPipeDialect) {
    override val stopsAtStageHead = false
    override fun inputCatalog(file: PsiFile, input: String): ShapeCatalog? = null
    override fun stagePhrase(phrase: String) = PrioritizedLookupElement.withPriority(
        LookupElementBuilder.create(phrase).bold().withTypeText("pipe stage", true), 90.0,
    )
}
