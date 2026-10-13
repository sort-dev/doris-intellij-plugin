package dev.sort.doris.brikk

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiLanguageInjectionHost
import dev.brikk.house.sql.intellij.host.HostSqlChannel
import dev.brikk.house.sql.intellij.host.HostSqlPlaceholderErrorFilter

interface DorisHostInspectionRule {
    fun isInspectionOnly(host: PsiLanguageInjectionHost): Boolean

    companion object {
        val EP = ExtensionPointName.create<DorisHostInspectionRule>("dev.sort.doris-intellij-plugin.hostInspectionRule")
    }
}

object DorisHostSqlChannel : HostSqlChannel("dev.sort.doris.brikk") {
    override fun claimsHost(host: PsiLanguageInjectionHost): Boolean =
        DorisHostInspectionRule.EP.extensionList.any { it.isInspectionOnly(host) }
}

class DorisHostPlaceholderErrorFilter : HostSqlPlaceholderErrorFilter(DorisHostSqlChannel)
