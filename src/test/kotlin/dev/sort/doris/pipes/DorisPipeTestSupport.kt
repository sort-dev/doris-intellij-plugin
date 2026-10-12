package dev.sort.doris.pipes

import dev.brikk.house.sql.intellij.SqlExecutionAdapter
import dev.brikk.house.sql.intellij.SqlExecutionRefusal
import dev.brikk.house.sql.intellij.RenderedSql
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.lower
import dev.brikk.house.sql.parser.PipeStageCut
import dev.brikk.house.sql.parser.PipeStageSplitter

internal fun DorisPipesEngine.transpile(text: String, allowNamedParameters: Boolean = true, allowFirstFromStage: Boolean = false) =
    DorisPipeDialect.lower(text, firstStage = allowFirstFromStage, substituted = !allowNamedParameters)

internal fun DorisPipesEngine.transpile(chunk: DorisPipes.Chunk) = DorisPipeDialect.lower(chunk.text)

internal val PipeTranslation.Lowered.dorisSql get() = sql
internal val PipeTranslation.Lowered.unsupportedMessages get() = result.unsupportedMessages
internal val PipeTranslation.Lowered.validationError get() =
    (verdict as? NativeVerdict.Rejected)?.let { SqlExecutionRefusal("Generated SQL failed validation: ${it.message}") }
internal val PipeTranslation.Lowered.executionError get() = blocker?.let(::SqlExecutionRefusal)
internal val PipeStageCut.Prefix.text get() = sql
internal val PipeStageCut.Prefix.totalStages get() = stageCount

internal fun DorisPipesEngine.stagePrefixAt(text: String, offset: Int) =
    PipeStageSplitter.topLevelPrefixAt(text, offset, "doris") as? PipeStageCut.Prefix

internal fun DorisPipesEngine.runnableStageCutAt(text: String, offset: Int) =
    PipeStageSplitter.topLevelPrefixAt(text, offset, "doris")

internal object DorisTestExecutionAdapter : SqlExecutionAdapter {
    override fun claims(text: String) = DorisPipes.containsPipeOperator(text)
    override fun parameters(text: String) = DorisPipeDialect.parameterSpans(text)
    override fun translate(text: String, beforeSubstitution: Boolean): RenderedSql =
        when (val result = DorisPipeDialect.lower(text, substituted = !beforeSubstitution)) {
            is PipeTranslation.Lowered -> {
                result.blocker?.let { throw SqlExecutionRefusal(it) }
                RenderedSql(result.sql, text)
            }
            is PipeTranslation.Refused -> throw SqlExecutionRefusal(result.message)
            PipeTranslation.NotPipe -> throw SqlExecutionRefusal("Not a PIPE statement")
        }
}
