package dev.sort.doris.sql

import com.intellij.openapi.components.service
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
    internal val baseline = completionSnapshot.completableFunctions() +
        FunctionDef("iceberg_meta", kind = FunctionKind.TABLE_VALUED).presentation()

    override fun snapshot(file: PsiFile) = completionSnapshot

    override fun functions(file: PsiFile) = service<DorisLiveCatalogService>().completion(file).functions

    override fun serverOnlyFunctions(file: PsiFile) = service<DorisLiveCatalogService>().completion(file).serverOnlyFunctions

    override fun builtinFunctionFilter(file: PsiFile) = service<DorisLiveCatalogService>().completion(file).builtinFunctionFilter

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

class DorisMetadataCompletionContributor : SqlCompletionContributor(DorisCompletionProfile)
class DorisCompletionConfidence : SqlCompletionConfidence(DorisCompletionProfile)
class DorisBuiltinPrototypeFilter : SqlBuiltinPrototypeFilter(DorisCompletionProfile)
class DorisAliasResolveExtension : SelectAliasResolveExtension(DorisDbms.DORIS)
