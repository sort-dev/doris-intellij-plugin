package dev.sort.doris.brikk

import com.intellij.openapi.project.DumbService
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

internal object BrikkDorisTemplate {
    private const val RUNTIME = "dev.brikk.house.sql.runtime"
    private const val ENTRY_POINT = "$RUNTIME.Sql.doris"
    private const val ANNOTATION = "$RUNTIME.BrikkSql"
    private const val REL = "$RUNTIME.Rel"

    data class Entry(val text: String, val problem: String? = null, val pending: Boolean = false)

    /** Only direct SQL arguments, optionally wrapped in the compiler's no-argument trims. */
    fun entryPoint(host: KtStringTemplateExpression): KtCallExpression? {
        var argument: KtExpression = host
        while (true) {
            val qualified = argument.parent as? KtDotQualifiedExpression ?: break
            if (qualified.receiverExpression != argument) break
            val trim = qualified.selectorExpression as? KtCallExpression ?: return null
            if (trim.calleeExpression?.text !in setOf("trimIndent", "trimMargin") || trim.valueArguments.isNotEmpty()) return null
            argument = qualified
        }
        val valueArgument = argument.parent as? KtValueArgument ?: return null
        return valueArgument.parent.parent as? KtCallExpression
    }

    fun isRecognized(host: KtStringTemplateExpression): Boolean {
        val call = entryPoint(host) ?: return false
        val function = PsiTreeUtil.getParentOfType(host, KtNamedFunction::class.java) ?: return false
        if (DumbService.isDumb(host.project)) return false
        return analyzeForInjection(host) {
            val reference = call.calleeExpression as? KtNameReferenceExpression ?: return@analyzeForInjection false
            val target = reference.mainReference.resolveToSymbol() as? KaCallableSymbol ?: return@analyzeForInjection false
            target.callableId?.asSingleFqName()?.asString() == ENTRY_POINT &&
                function.symbol.annotations.any { it.classId?.asSingleFqName()?.asString() == ANNOTATION }
        }
    }

    fun classify(expression: KtExpression?): Entry {
        if (expression == null) return pending()
        // Literal entries such as ${'$'} are compiler-supported text, not Kotlin references.
        literal(expression)?.let { return Entry(it) }
        if (expression !is KtNameReferenceExpression) {
            return rejected("Only simple Kotlin references can be interpolated in Brikk SQL; extract this expression to a val")
        }
        if (DumbService.isDumb(expression.project)) return pending()
        return analyzeForInjection(expression) {
            when (val symbol = expression.mainReference.resolveToSymbol()) {
                is KaLocalVariableSymbol -> Entry(":${expression.getReferencedName()}")
                is KaValueParameterSymbol -> {
                    if (symbol.returnType is KaErrorType) return@analyzeForInjection pending()
                    val name = expression.getReferencedName()
                    if ((symbol.returnType as? KaClassType)?.classId?.asSingleFqName()?.asString() == REL) {
                        val following = expression.parent.nextSibling?.text.orEmpty().trimStart()
                        Entry(name, if (following.startsWith("(")) null else "Rel parameter '$name' must be followed by a slot call")
                    } else Entry(":$name")
                }
                is KaKotlinPropertySymbol -> {
                    if (!symbol.isConst) return@analyzeForInjection Entry(":${expression.getReferencedName()}")
                    if (symbol.origin == KaSymbolOrigin.LIBRARY) {
                        // Compiled const metadata is already a literal, as in deserialized FIR.
                        val constant = expression.evaluate()
                        if (constant != null && constant !is KaConstantValue.ErrorValue) {
                            return@analyzeForInjection Entry(constant.value.toString())
                        }
                    }
                    val property = symbol.psi as? KtProperty
                    val text = property?.initializer?.let { literal(it) }
                    if (text != null) Entry(text)
                    else rejected("Computed const val '${expression.getReferencedName()}' cannot be interpolated as SQL; use a literal initializer")
                }
                null -> pending()
                else -> rejected("This declaration cannot be interpolated in Brikk SQL")
            }
        }
    }

    private fun rejected(message: String): Entry = Entry(":__brikk_unsupported", message)
    private fun pending(): Entry = Entry(":__brikk_pending", pending = true)

    private fun literal(expression: KtExpression): String? = when (expression) {
        is KtStringTemplateExpression -> if (expression.hasInterpolation()) null else buildString {
            for (entry in expression.entries) append((entry as? KtEscapeStringTemplateEntry)?.unescapedValue ?: entry.text)
        }
        is KtConstantExpression -> analyzeForInjection(expression) {
            expression.evaluate()?.takeUnless { it is KaConstantValue.ErrorValue }?.let { it.value.toString() }
        }
        // FIR represents a signed numeric literal as a literal too. Do not fold computed constants.
        is KtPrefixExpression -> if (expression.operationToken in setOf(KtTokens.MINUS, KtTokens.PLUS) &&
            expression.baseExpression is KtConstantExpression) {
            analyzeForInjection(expression) {
                expression.evaluate()?.takeUnless { it is KaConstantValue.ErrorValue }?.let { it.value.toString() }
            }
        } else null
        else -> null
    }

    // Fragment editors can request injection on the EDT. Keep analysis bounded to this host.
    @OptIn(KaAllowAnalysisOnEdt::class)
    private inline fun <T> analyzeForInjection(element: KtElement, crossinline action: KaSession.() -> T): T =
        allowAnalysisOnEdt { analyze(element) { action() } }
}
