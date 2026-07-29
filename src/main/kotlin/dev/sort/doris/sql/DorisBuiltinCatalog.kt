package dev.sort.doris.sql

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-data-source, in-memory (session-scoped) set of the builtin function NAMES the connected Doris
 * FE actually reports via `SHOW BUILTIN FUNCTIONS`, harvested once per data source at connect by
 * [DorisBuiltinFunctionInterceptor].
 *
 * The brikk-sql catalog ([DorisFunctions]) is the baseline — it carries names AND signatures, pinned
 * to a Doris release. This store reconciles that baseline to the *connected server's* version:
 * brikk names the server doesn't report are dropped, server names brikk lacks are offered bare
 * (the server gives no signatures). With no harvested data — never connected, an older server
 * without the command, or a failed harvest — the brikk baseline stands unchanged. Nothing is
 * persisted; each IDE session re-harvests on first connect.
 */
object DorisBuiltinCatalog {

    private val byDataSource = ConcurrentHashMap<String, Set<String>>()

    /** Record the server's builtin names (upper-cased) for a data source. Empty sets are ignored. */
    fun record(dataSourceId: String, upperNames: Set<String>) {
        if (upperNames.isNotEmpty()) byDataSource[dataSourceId] = upperNames
    }

    /** True once a harvested set exists for [dataSourceId] — the interceptor harvests only if not. */
    fun hasData(dataSourceId: String?): Boolean =
        dataSourceId != null && byDataSource.containsKey(dataSourceId)

    private fun served(dataSourceId: String?): Set<String>? = dataSourceId?.let { byDataSource[it] }

    /**
     * Whether a brikk baseline function [upperName] should be offered for [dataSourceId]. No
     * harvested data → keep every brikk name (baseline); with data → keep only names the server has.
     */
    fun isServed(upperName: String, dataSourceId: String?): Boolean {
        val s = served(dataSourceId) ?: return true
        return upperName in s
    }

    /**
     * Server builtin names not in the brikk baseline [brikkNames] (all upper-cased) — offered as
     * bare items. Empty when there is no harvested data for [dataSourceId].
     */
    fun extraNames(brikkNames: Set<String>, dataSourceId: String?): Set<String> {
        val s = served(dataSourceId) ?: return emptySet()
        return s - brikkNames
    }

    /** The data-source id backing [file]'s running console, or null for an unattached file. */
    fun dataSourceIdFor(file: PsiFile): String? {
        val vf = file.viewProvider.virtualFile
        val console = JdbcConsoleProvider.getRunningConsoles(file.project).firstOrNull { c ->
            runCatching { c.session.clientsWithFile.any { it.virtualFile == vf } }.getOrDefault(false)
        } ?: return null
        return runCatching { console.session.connectionPoint.dataSource.uniqueId }.getOrNull()
    }

    /** Test hook: forget all harvested data. */
    fun clear() = byDataSource.clear()
}
