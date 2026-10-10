package dev.sort.doris.brikk

import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkKotlinContext
import dev.brikk.house.intellij.ContextResult
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Optional Kotlin integration only. Brikk semantics belong to the shared helper. */
internal object BrikkDorisTemplate {
    fun inspect(host: KtStringTemplateExpression, includeShapes: Boolean = false): ContextResult =
        when (val result = BrikkKotlinContext.inspect(host, includeShapes)) {
            is ContextResult.Available ->
                if (result.context.dialect == BrikkDialect.DORIS) result else ContextResult.NotBrikk
            else -> result
        }

    fun isRecognized(host: KtStringTemplateExpression): Boolean = inspect(host) is ContextResult.Available
}
