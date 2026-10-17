package dev.sort.doris.brikk

import dev.brikk.house.sql.intellij.completion.SqlLookupIcons
import dev.brikk.house.sql.intellij.host.HostSqlClaim
import dev.brikk.house.sql.intellij.host.HostSqlLineMarkerProvider
import dev.brikk.house.sql.intellij.host.HostSqlState
import dev.sort.doris.pipes.DorisPipes

class BrikkDorisLineMarkerProvider : HostSqlLineMarkerProvider(
    DorisHostSqlChannel, "Brikk Doris SQL inspection views", SqlLookupIcons.BrikkColumn,
) {
    override fun tooltip(state: HostSqlState): String {
        val features = buildList {
            add("Kotlin binds")
            add("relation contracts")
            if (System.getProperty(DorisPipes.PROPERTY) != "false") add("PIPE")
        }.joinToString(", ")
        val pending = state.claim is HostSqlClaim.Unavailable || (state.claim as? HostSqlClaim.Injected)?.pending == true
        return "Brikk DorisSQL: $features; inspection only, not executable" +
            if (pending) "; waiting for indexing" else ""
    }
}
