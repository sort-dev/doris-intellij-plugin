package dev.sort.doris.pipes

import com.intellij.database.script.ScriptModel
import com.intellij.database.script.translator.TranslateException
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.SyntaxTraverser
import com.intellij.util.Function
import dev.brikk.house.sql.intellij.RenderedSql
import dev.brikk.house.sql.intellij.SqlExecutionAdapter
import dev.brikk.house.sql.intellij.SqlExecutionRefusal
import dev.brikk.house.sql.intellij.SqlParameterSpan
import dev.brikk.house.sql.intellij.TranslatingScriptModel

/** Doris owns translation approval and native colon syntax, not platform model preparation. */
internal class DorisExecutionAdapter(private val allowFirstFromStage: Boolean = false) : SqlExecutionAdapter {
    var lastTranslation: DorisPipesEngine.Transpile.Ok? = null
        private set
    var lastRefusal: DorisPipesEngine.Transpile.Err? = null
        private set

    override fun claims(text: String): Boolean = DorisPipes.containsPipeOperator(text) ||
        allowFirstFromStage && DorisPipes.looksLikePipeChunk(text)

    override fun parameters(text: String): List<SqlParameterSpan> = DorisPipes.namedParameterRanges(text).map {
        SqlParameterSpan(text.substring(it.first + 1, it.last + 1), it.first, it.last + 1)
    }

    override fun translate(text: String, beforeSubstitution: Boolean): RenderedSql {
        val translated = when (val result = DorisPipesEngine.transpile(text, beforeSubstitution, allowFirstFromStage)) {
            is DorisPipesEngine.Transpile.Ok -> {
                result.executionError?.let { refuse(it) }
                result
            }
            is DorisPipesEngine.Transpile.Err -> refuse(result)
            DorisPipesEngine.Transpile.NotPipe -> refuse(DorisPipesEngine.Transpile.Err(
                null, null, "PIPE statement was not translated: $text",
            ))
        }
        lastTranslation = translated
        val result = checkNotNull(translated.result)
        check(result.sql == translated.dorisSql)
        val trim = text.length - text.trimStart().length
        // The callback accepts Doris's 1-based line and 0-based code-point position.
        return RenderedSql(result.sql, text) map@ { line, position ->
            val mapped = DorisPipesEngine.mapServerErrorExact("(line $line, pos $position)", result) ?: return@map null
            val start = mapped.startOffset?.plus(trim) ?: return@map null
            val end = mapped.endOffset?.plus(trim)?.plus(1) ?: return@map null
            if (start < 0 || end > text.length || start >= end) return@map null
            TextRange(start, end)
        }
    }

    private fun refuse(error: DorisPipesEngine.Transpile.Err): Nothing {
        lastRefusal = error
        throw SqlExecutionRefusal(error.message)
    }

    fun error(failure: SqlExecutionRefusal): DorisPipesEngine.Transpile.Err = lastRefusal
        ?: DorisPipesEngine.Transpile.Err(null, null, failure.message ?: "PIPE preparation refused")
}

/** Only Doris's request/auditor metadata lives here. The helper owns copied platform iterators. */
internal data class PipePlan(
    val sourceQuery: String,
    val dorisSql: String,
    val anchor: PipeAnchor,
    val authoredMap: ((Int, Int) -> TextRange?)?,
)

internal class PipeScriptModel<E>(
    private val delegate: ScriptModel<E>,
    private val editor: Editor,
    private val allowFirstFromStage: Boolean = false,
) : ScriptModel<E>() {
    private val adapter = DorisExecutionAdapter(allowFirstFromStage)
    private val model = try {
        TranslatingScriptModel(delegate, editor, adapter)
    } catch (failure: SqlExecutionRefusal) {
        throw PipeTranslationFailure(adapter.error(failure))
    }
    private val documentHash = editor.document.text.hashCode()
    private var finalized = false

    val executionPlans: List<PipePlan?> get() {
        check(finalized) { "PIPE execution must be finalized before tracking" }
        return model.statements().map { statement ->
            val text = statement.text()
            if (!adapter.claims(text)) return@map null
            val range = statement.range().shiftRight(Math.toIntExact(statement.rangeOffset()))
            PipePlan(text, statement.query(),
                PipeAnchor(editor, range, model.virtualFile, range.startOffset, documentHash),
                model.authoredMap(statement.range(), statement.rangeOffset()))
        }.toList()
    }
    val plans: List<PipePlan> get() = executionPlans.filterNotNull()

    fun translated(storage: PStorage): DorisPipesEngine.Transpile.Ok {
        finalizeTranslations(storage)?.let { throw PipeTranslationFailure(it) }
        val plan = plans.single()
        return checkNotNull(adapter.lastTranslation).also { check(it.dorisSql == plan.dorisSql) }
    }

    fun finalizeTranslations(storage: PStorage): DorisPipesEngine.Transpile.Err? {
        try {
            model.finalizeTranslations(storage)
            finalized = true
        } catch (failure: SqlExecutionRefusal) {
            return adapter.error(failure)
        } catch (failure: TranslateException) {
            return DorisPipesEngine.Transpile.Err(null, null, failure.message ?: "Parameter substitution failed")
        }
        return null
    }

    override fun isActual() = model.isActual
    override fun subModel(range: TextRange?): ScriptModel<E> = PipeScriptModel(delegate.subModel(range), editor, allowFirstFromStage)
    override fun everything() = model.everything()
    override fun statements() = model.statements()
    override fun parameters() = model.parameters()
    override fun externals() = model.externals()
    override fun getVirtualFile() = model.virtualFile
    override fun getTextRange() = model.textRange
    override fun getLanguage() = model.language
    override fun <EE : Any?> rawTransform(transform: Function<in SyntaxTraverser<E>, out SyntaxTraverser<EE>>): ScriptModel<EE> =
        PipeScriptModel(delegate.rawTransform(transform), editor, allowFirstFromStage)
}

/** No guessed authored coordinates when the helper withheld its map after parameter expansion. */
internal fun mappedPipeError(message: String, source: String, map: ((Int, Int) -> TextRange?)?): DorisPipes.MappedError? {
    if (map == null) return null
    val match = DorisPipes.SERVER_POSITION.find(message) ?: return null
    val line = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val position = match.groupValues[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val range = map(line, position) ?: return null
    return DorisPipes.MappedError(null, 1 + source.take(range.startOffset).count { it == '\n' },
        line, position, range.startOffset, range.endOffset - 1)
}
