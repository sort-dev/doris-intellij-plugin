package dev.sort.doris.brikk

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionPerformer
import com.intellij.openapi.util.Key
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.lang.injection.InjectedLanguageManager
import dev.brikk.house.intellij.ContextResult
import dev.brikk.house.intellij.HostRange
import org.jetbrains.kotlin.idea.base.injection.KotlinLanguageInjectionPerformerBase
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Delegates trimming, escapes and injection shreds to Kotlin's own performer. */
class BrikkDorisInjectionPerformer : LanguageInjectionPerformer {
    override fun isPrimary(): Boolean = true

    override fun performInjection(registrar: MultiHostRegistrar, injection: Injection, context: PsiElement): Boolean {
        val host = context as? KtStringTemplateExpression ?: return false
        // The host is available while the injected file is being parsed, before registrar file
        // metadata is applied. Clear it even when an edit removes the Brikk annotation/call.
        if (injection.injectedLanguageId != "DorisSQL") {
            withdraw(host)
            return false
        }
        val result = BrikkDorisTemplate.inspect(host)
        val brikk = when (result) {
            is ContextResult.Available -> result.context
            is ContextResult.Unavailable -> {
                withdraw(host)
                retryWhenReady(host)
                // No virtual document is safer than the native performer's guessed bare names.
                return true
            }
            ContextResult.NotBrikk -> {
                withdraw(host)
                return false
            }
        }
        host.putUserData(BrikkDorisContext.PROFILE, true)
        val hostPointer = SmartPointerManager.getInstance(host.project).createSmartPsiElementPointer(host)
        host.putUserData(BrikkDorisContext.RELATIONS, BrikkDorisContext.RelationProvider {
            hostPointer.element?.let { BrikkDorisTemplate.inspect(it, includeShapes = true) }
                ?: ContextResult.Unavailable("Kotlin SQL host is no longer available")
        })
        if (brikk.trims.firstOrNull() == "trimMargin") {
            // The compiler supports the no-argument form. Kotlin's contributor can omit the
            // default margin character; supply it to Kotlin's own range handler, not a text rewrite.
            BrikkKotlinIndentBridge.useDefaultMargin(host)
        }
        val entries = brikk.entries.associateBy { it.range }
        val uncertain = brikk.uncertain
        host.putUserData(BrikkDorisContext.UNCERTAIN, uncertain)
        val performer = object : KotlinLanguageInjectionPerformerBase() {
            // Despite the hook's name, its result is the virtual text for a template entry.
            // Never evaluate scalar properties or getters.
            override fun tryEvaluateConstant(expression: KtExpression?): String? {
                val entry = expression?.parent ?: return null
                return entries[HostRange(entry.startOffsetInParent, entry.startOffsetInParent + entry.textLength)]?.replacement
            }
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

    private fun withdraw(host: KtStringTemplateExpression) {
        host.putUserData(BrikkDorisContext.PROFILE, null)
        host.putUserData(BrikkDorisContext.UNCERTAIN, null)
        host.putUserData(BrikkDorisContext.RELATIONS, null)
    }

    private fun retryWhenReady(host: KtStringTemplateExpression) {
        val project = host.project
        val revision = PsiModificationTracker.getInstance(project).modificationCount
        // One deferred retry per revision, not a negative classification cache. Later source,
        // import and module changes still cause a fresh helper inspection on the normal path.
        if (host.getUserData(RETRY_REVISION) == revision) return
        host.putUserData(RETRY_REVISION, revision)
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(host)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            DumbService.getInstance(project).runWhenSmart {
                ReadAction.run<RuntimeException> {
                    val file = pointer.element?.containingFile ?: return@run
                    InjectedLanguageManager.getInstance(project).dropFileCaches(file)
                    DaemonCodeAnalyzer.getInstance(project).restart(file)
                }
            }
        }
    }

    private companion object {
        val RETRY_REVISION: Key<Long> = Key.create("doris.brikkHouse.retryRevision")
    }
}
