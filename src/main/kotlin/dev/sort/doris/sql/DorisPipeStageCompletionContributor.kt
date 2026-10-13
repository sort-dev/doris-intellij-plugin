package dev.sort.doris.sql

import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.pipe.PipeStageCompletionContributor
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.sort.doris.pipes.DorisPipeDialect

class DorisPipeStageCompletionContributor : PipeStageCompletionContributor(DorisPipeDialect) {
    override fun inputCatalog(file: PsiFile, input: String): ShapeCatalog? = null
}
