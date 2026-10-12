package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.completion.ExpressionPositions
import dev.brikk.house.sql.intellij.completion.KeywordScope
import dev.brikk.house.sql.intellij.completion.LookupCase
import dev.brikk.house.sql.intellij.completion.QualifierRule
import dev.brikk.house.sql.intellij.completion.SqlBuiltinPrototypeFilter
import dev.brikk.house.sql.intellij.completion.SqlCompletionConfidence
import dev.brikk.house.sql.intellij.completion.SqlCompletionContributor
import dev.brikk.house.sql.intellij.completion.SqlCompletionProfile
import dev.brikk.house.sql.intellij.completion.SqlFunctionGates
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.completion.SqlLookupStyles
import dev.brikk.house.sql.intellij.completion.completableFunctions
import dev.brikk.house.sql.intellij.psi.SelectAliasResolveExtension
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.brikk.house.sql.metadata.presentation
import dev.sort.doris.DorisDbms

object DorisCompletionProfile : SqlCompletionProfile {
    override val language get() = DorisSqlDialect.INSTANCE
    override val metadata = DORIS_METADATA
    private val completionSnapshot = metadata.default.let {
        MetadataSnapshot(
            engineVersion = it.engineVersion,
            functions = it.functions,
            keywords = it.keywords,
            pragmas = it.pragmas,
            settings = it.settings,
            extensions = it.extensions,
            extensionFunctions = it.extensionFunctions,
            tableFunctions = DorisTableFunctions.all,
            grammarForms = it.grammarForms,
        )
    }
    private val baseline = completionSnapshot.completableFunctions()
    private val retainedTvfs = listOf(FunctionDef("iceberg_meta", kind = FunctionKind.TABLE_VALUED).presentation())
    private val names = (baseline + retainedTvfs).mapTo(HashSet()) { it.name.uppercase() }

    override fun snapshot(file: PsiFile) = completionSnapshot

    private data class DataSourceContext(val file: PsiFile, val id: String?)
    private val dataSourceContext = ThreadLocal<DataSourceContext>()

    internal fun withDataSource(file: PsiFile, complete: () -> Unit) {
        val previous = dataSourceContext.get()
        dataSourceContext.set(DataSourceContext(file, DorisBuiltinCatalog.dataSourceIdFor(file)))
        try {
            complete()
        } finally {
            if (previous == null) dataSourceContext.remove() else dataSourceContext.set(previous)
        }
    }

    private fun dataSourceId(file: PsiFile): String? {
        val context = dataSourceContext.get()
        return if (context?.file === file) context.id else DorisBuiltinCatalog.dataSourceIdFor(file)
    }

    override fun functions(file: PsiFile) = dataSourceId(file).let { id ->
        baseline.filter {
            metadata.default.functions.getAll(it.name).isEmpty() || DorisBuiltinCatalog.isServed(it.name.uppercase(), id)
        } + retainedTvfs
    }

    override fun serverOnlyFunctions(file: PsiFile) =
        DorisBuiltinCatalog.extraNames(names, dataSourceId(file))
            .filter { completionSnapshot.tableFunction(it)?.queryable != false }
            .map { FunctionDef(it, kind = FunctionKind.SCALAR).presentation() }

    override fun builtinFunctionFilter(file: PsiFile): ((String) -> Boolean)? {
        val id = dataSourceId(file)
        if (!DorisBuiltinCatalog.hasData(id)) return null
        return { name -> !DorisBuiltinCatalog.suppresses(name.uppercase(), id, metadata.default.functions.isKnown(name)) }
    }

    override val styles = SqlLookupStyles.DEFAULT.let { defaults ->
        defaults.with(
            *listOf(
                SqlLookupKind.FUNCTION to "Doris function",
                SqlLookupKind.AGGREGATE to "Doris aggregate",
                SqlLookupKind.WINDOW to "Doris window function",
                SqlLookupKind.TABLE_FUNCTION to "Doris table function",
            ).map { (kind, label) ->
                kind to defaults[kind].copy(typeText = label, case = LookupCase.LOWER,
                    showsSignature = true, showsReturnType = true)
            }.toTypedArray(),
            SqlLookupKind.SERVER_FUNCTION to defaults[SqlLookupKind.SERVER_FUNCTION].copy(typeText = "Doris function"),
        )
    }
    override val functionGates = SqlFunctionGates(QualifierRule.Unqualified, digitPrefix = true,
        autopopup = ExpressionPositions.STANDARD.let {
            ExpressionPositions(it.keywords + setOf("XOR", "DIV", "MOD"),
                it.operators + setOf("<=>", "&", "|", "^", ":="))
        })
    override val keywords = KeywordScope.NONE
    override val propertyQuote = '"'
}

class DorisCompletionContextContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (!parameters.originalFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return
        DorisCompletionProfile.withDataSource(parameters.originalFile) {
            result.runRemainingContributors(parameters, result::passResult)
        }
    }
}

class DorisMetadataCompletionContributor : SqlCompletionContributor(DorisCompletionProfile)
class DorisCompletionConfidence : SqlCompletionConfidence(DorisCompletionProfile)
class DorisBuiltinPrototypeFilter : SqlBuiltinPrototypeFilter(DorisCompletionProfile)
class DorisAliasResolveExtension : SelectAliasResolveExtension(DorisDbms.DORIS)
