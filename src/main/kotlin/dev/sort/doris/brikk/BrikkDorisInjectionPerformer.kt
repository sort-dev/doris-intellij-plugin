package dev.sort.doris.brikk

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionPerformer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.base.injection.KotlinLanguageInjectionPerformerBase
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression

/** Delegates trimming, escapes and injection shreds to Kotlin's own performer. */
class BrikkDorisInjectionPerformer : LanguageInjectionPerformer {
    override fun isPrimary(): Boolean = true

    override fun performInjection(registrar: MultiHostRegistrar, injection: Injection, context: PsiElement): Boolean {
        val host = context as? KtStringTemplateExpression ?: return false
        // The host is available while the injected file is being parsed, before registrar file
        // metadata is applied. Clear it even when an edit removes the Brikk annotation/call.
        if (injection.injectedLanguageId != "DorisSQL" || !BrikkDorisTemplate.isRecognized(host)) {
            host.putUserData(BrikkDorisContext.PROFILE, null)
            host.putUserData(BrikkDorisContext.UNCERTAIN, null)
            return false
        }
        host.putUserData(BrikkDorisContext.PROFILE, true)
        val trim = (host.parent as? KtDotQualifiedExpression)?.selectorExpression as? KtCallExpression
        if (trim?.calleeExpression?.text == "trimMargin") {
            // The compiler supports the no-argument form. Kotlin's contributor can omit the
            // default margin character; supply it to Kotlin's own range handler, not a text rewrite.
            BrikkKotlinIndentBridge.useDefaultMargin(host)
        }
        val entries = host.entries.filterIsInstance<KtStringTemplateEntryWithExpression>()
            .associate { it.expression to BrikkDorisTemplate.classify(it.expression) }
        val uncertain = entries.values.any { it.pending || it.problem != null }
        host.putUserData(BrikkDorisContext.UNCERTAIN, uncertain)
        val performer = object : KotlinLanguageInjectionPerformerBase() {
            // Despite the hook's name, its result is the virtual text for a template entry.
            // Never evaluate scalar properties or getters.
            override fun tryEvaluateConstant(expression: KtExpression?): String = entries[expression]?.text ?: ":__brikk_pending"
        }
        val scopedRegistrar = object : MultiHostRegistrar by registrar {
            override fun startInjecting(language: Language): MultiHostRegistrar {
                registrar.startInjecting(language)
                registrar.putInjectedFileUserData(BrikkDorisContext.PROFILE, true)
                registrar.putInjectedFileUserData(BrikkDorisContext.UNCERTAIN, uncertain)
                registrar.makeInspectionsLenient(uncertain)
                return this
            }
            override fun startInjecting(language: Language, extension: String?): MultiHostRegistrar {
                registrar.startInjecting(language, extension)
                registrar.putInjectedFileUserData(BrikkDorisContext.PROFILE, true)
                registrar.putInjectedFileUserData(BrikkDorisContext.UNCERTAIN, uncertain)
                registrar.makeInspectionsLenient(uncertain)
                return this
            }
            override fun <T> putInjectedFileUserData(key: Key<T>, value: T?): MultiHostRegistrar {
                registrar.putInjectedFileUserData(key, value)
                return this
            }
            override fun frankensteinInjection(value: Boolean): MultiHostRegistrar {
                registrar.frankensteinInjection(value || uncertain)
                return this
            }
            override fun makeInspectionsLenient(value: Boolean): MultiHostRegistrar {
                registrar.makeInspectionsLenient(value || uncertain)
                return this
            }
        }
        return performer.performInjection(scopedRegistrar, injection, context)
    }
}
