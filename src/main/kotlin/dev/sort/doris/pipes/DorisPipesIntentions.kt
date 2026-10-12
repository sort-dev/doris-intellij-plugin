package dev.sort.doris.pipes

import dev.brikk.house.sql.intellij.pipe.PipePreviewAction
import dev.brikk.house.sql.intellij.pipe.PipePreviewIntention
import dev.brikk.house.sql.intellij.pipe.PipeRunToStageAction
import dev.brikk.house.sql.intellij.pipe.PipeRunToStageIntention

class PreviewPipeSqlIntention : PipePreviewIntention(DorisPipeDialect)
class RunPipesToStageIntention : PipeRunToStageIntention(DorisPipeDialect)
class PreviewPipeSqlAction : PipePreviewAction(DorisPipeDialect)
class RunPipesToStageAction : PipeRunToStageAction(DorisPipeDialect)
