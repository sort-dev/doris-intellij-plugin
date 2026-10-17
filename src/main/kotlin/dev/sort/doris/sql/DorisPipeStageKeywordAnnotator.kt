package dev.sort.doris.sql

import dev.brikk.house.sql.intellij.pipe.PipeStageKeywordAnnotator
import dev.sort.doris.pipes.DorisPipeDialect

class DorisPipeStageKeywordAnnotator : PipeStageKeywordAnnotator(DorisPipeDialect)
