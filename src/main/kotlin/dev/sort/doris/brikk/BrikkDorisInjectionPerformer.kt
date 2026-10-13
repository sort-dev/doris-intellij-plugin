package dev.sort.doris.brikk

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.components.service
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.SmartPointerManager
import dev.brikk.house.intellij.BrikkContext
import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkHostState
import dev.brikk.house.intellij.BrikkHosts
import dev.brikk.house.intellij.BrikkInspectionView
import dev.brikk.house.intellij.ContextResult
import dev.brikk.house.sql.intellij.host.HostSqlClaim
import dev.brikk.house.sql.intellij.host.HostSqlEntries
import dev.brikk.house.sql.intellij.host.HostSqlEntry
import dev.brikk.house.sql.intellij.host.HostSqlInjector
import dev.brikk.house.sql.intellij.host.HostSqlTrim
import dev.sort.doris.sql.DorisSqlDialect
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkDorisInspectionRule : DorisHostInspectionRule {
    override fun isInspectionOnly(host: PsiLanguageInjectionHost) = BrikkInspectionView.isInspectionOnly(host)
}

class BrikkDorisInjectionPerformer : HostSqlInjector(DorisHostSqlChannel, DorisSqlDialect.INSTANCE) {
    override fun claim(host: PsiLanguageInjectionHost): HostSqlClaim = claimHost(host)

    internal fun claimHost(host: PsiLanguageInjectionHost): HostSqlClaim {
        val kotlin = host as? KtStringTemplateExpression ?: return HostSqlClaim.NotClaimed
        val state = BrikkHosts.state(kotlin)
        if (state == BrikkHostState.NotBrikk || state.dialect != BrikkDialect.DORIS) {
            withdraw(kotlin)
            return HostSqlClaim.NotClaimed
        }
        val context = when (state) {
            is BrikkHostState.Recognized -> state.context
            is BrikkHostState.Pending -> state.projection
            BrikkHostState.NotBrikk -> null
        }
        kotlin.putUserData(BrikkDorisContext.PROFILE, true)
        kotlin.putUserData(BrikkDorisContext.UNCERTAIN, state is BrikkHostState.Pending || context?.uncertain == true)
        if (state is BrikkHostState.Pending || context?.uncertain == true) host.project.service<BrikkDorisHostRefresh>().watch(kotlin)
        val pointer = SmartPointerManager.getInstance(host.project).createSmartPsiElementPointer(kotlin)
        kotlin.putUserData(BrikkDorisContext.RELATIONS, BrikkDorisContext.RelationProvider {
            pointer.element?.let { BrikkDorisTemplate.inspect(it, includeShapes = true) }
                ?: ContextResult.Unavailable("Kotlin SQL host is no longer available")
        })
        if (context == null) return HostSqlClaim.Unavailable((state as BrikkHostState.Pending).reason)
        return HostSqlClaim.Injected(DorisSqlDialect.INSTANCE, entries(context),
            pending = state is BrikkHostState.Pending, data = context)
    }

    private fun entries(context: BrikkContext) = HostSqlEntries(context.entries.map {
        HostSqlEntry(TextRange(it.range.start, it.range.end), it.replacement, it.placeholder)
    }, context.trimSteps.map { trim ->
        when (trim.function) {
            "trimIndent" -> HostSqlTrim.Indent
            "trimMargin" -> HostSqlTrim.Margin(checkNotNull(trim.marginPrefix))
            else -> error("Unsupported Brikk trim ${trim.function}")
        }
    })

    private fun withdraw(host: KtStringTemplateExpression) {
        host.putUserData(BrikkDorisContext.PROFILE, null)
        host.putUserData(BrikkDorisContext.UNCERTAIN, null)
        host.putUserData(BrikkDorisContext.RELATIONS, null)
    }
}
