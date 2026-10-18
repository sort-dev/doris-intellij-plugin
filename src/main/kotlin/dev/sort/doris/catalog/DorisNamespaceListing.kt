package dev.sort.doris.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.mssql.model.MsDatabase
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint
import dev.brikk.house.sql.intellij.catalog.helperRequestor
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisStringUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Doris's cheap namespace listing, on a helper connection of its own (never a console's session or
 * transaction, never the EDT), writing only names into the model: the catalogs (`SHOW CATALOGS`) and a
 * catalog's databases (`SHOW DATABASES FROM c`). The shared auto-introspection asks for them through
 * [DorisAutoIntrospectService.listNamespaces] while it finds a typed path; a console switch asks here
 * directly ([ensureNamespace]). A cleared data source (no model, or a scope without a catalog level) gets
 * its default scope and, once per session, the platform's general refresh, before anything else loads.
 */
open class DorisNamespaceListing(private val project: Project, private val scope: CoroutineScope) {
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap<String, Job>()
    private val listedRoots = ConcurrentHashMap.newKeySet<String>()

    /**
     * Whether Doris listed [local]'s catalogs itself, after a clear: its model holds names only, so a
     * catalog's databases are listed the same cheap way. A model the platform introspected keeps the
     * platform's refresh of a catalog.
     */
    fun listsCatalogsOf(local: LocalDataSource): Boolean = local.uniqueId in listedRoots

    /** The names a listing found: catalog names, then the databases of [catalog] when it was listed. */
    class Listing(val catalogs: List<Pair<Long, String>>?, val catalog: String?, val databases: List<String>?)

    /**
     * The catalogs of [local], for the shared auto-introspection finding [requested], and the databases of the
     * catalog it starts with (or of `internal`, where a connection starts, when its first name is no catalog):
     * written into the model, that catalog marked loaded, then a cleared data source's default scope and
     * general refresh. Waits for a console switch's listing of it first.
     */
    suspend fun listRoots(local: LocalDataSource, requested: List<String>): Boolean {
        running[local.uniqueId]?.join()
        val listing = try {
            withQueries(local) { query ->
                val catalogs = listCatalogs(query)
                val catalog = catalogs.firstOrNull { it.second.equals(requested.firstOrNull(), ignoreCase = true) }?.second
                    ?: catalogs.firstOrNull { it.second.equals(DorisCatalogScopes.INTERNAL_CATALOG, ignoreCase = true) }?.second
                val databases = catalog?.let { listDatabases(query, it) }
                DorisCatalogs.info("namespace listing on '${local.name}': catalogs -> ${catalogs.map { it.second }}" +
                    (databases?.let { "; catalog '$catalog' databases -> $it" } ?: ""))
                Listing(catalogs, catalog.takeIf { databases != null }, databases)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DorisCatalogs.warn("namespace listing of the catalogs of '${local.name}' failed: ${e.message}")
            return false
        }
        listedRoots += local.uniqueId
        return written(local, listing)
    }

    /** The databases of [catalog] of [local], for the shared auto-introspection, which counts them as its whole list. */
    suspend fun listDatabasesOf(local: LocalDataSource, catalog: String): Boolean {
        running[local.uniqueId]?.join()
        val databases = try {
            withQueries(local) { query -> listDatabases(query, catalog) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DorisCatalogs.warn("namespace listing of catalog '$catalog' of '${local.name}' failed: ${e.message}")
            return false
        }
        DorisCatalogs.info("namespace listing on '${local.name}': catalog '$catalog' databases -> $databases")
        return written(local, Listing(null, catalog, databases))
    }

    private suspend fun written(local: LocalDataSource, listing: Listing): Boolean {
        val cleared = withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext false
            write(local, listing).also { DbPsiFacade.getInstance(project).clearCaches() }
        }
        // Every database of the catalog is listed now: the shared auto-introspection need not load it again.
        if (listing.catalogs != null) listing.catalog?.let { project.service<DorisAutoIntrospectService>().markLoaded(local, listOf(it)) }
        if (cleared) syncCleared(local)
        return !project.isDisposed
    }

    private fun track(local: LocalDataSource, job: Job) {
        running[local.uniqueId] = job
        job.invokeOnCompletion { running.remove(local.uniqueId, job) }
    }

    private fun listCatalogs(query: (String) -> List<Map<String, String?>>): List<Pair<Long, String>> =
        query("SHOW CATALOGS").mapNotNull { row ->
            val name = row["CatalogName"] ?: return@mapNotNull null
            (row["CatalogId"]?.toLongOrNull() ?: name.hashCode().toLong()) to name
        }

    private fun listDatabases(query: (String) -> List<Map<String, String?>>, catalog: String): List<String> =
        query("SHOW DATABASES FROM " + DorisStringUtils.quoteIdentifier(catalog)).mapNotNull { it.values.firstOrNull() }

    /** Lists the catalogs of [local] and, after a console switch, the databases of [catalog] if the model lacks them. */
    fun ensureNamespace(local: LocalDataSource, catalog: String?, database: String?) {
        if (!DorisCatalogs.enabled || catalog.isNullOrBlank()) return
        // Never beside a running introspection: it writes the same model, and it lists catalogs itself.
        if (com.intellij.database.dataSource.DataSourceSyncManager.getInstance().isActive(local)) return
        val root = (local.model as? BasicModModel)?.root as? MsRoot
        val node = root?.databases?.firstOrNull { it.name.equals(catalog, ignoreCase = true) }
        val listCatalogs = node == null && claim(local, "catalogs")
        val listDatabases = (node == null || node.schemas.isEmpty || (database != null && node.schemas.none { it.name.equals(database, ignoreCase = true) })) &&
            claim(local, "databases of $catalog")
        if (!listCatalogs && !listDatabases) return
        DorisCatalogs.info("namespace listing after console switch to '$catalog${database?.let { ".$it" }.orEmpty()}' on '${local.name}'")
        track(local, scope.launch(Dispatchers.IO) {
            val listing = try {
                withQueries(local) { query ->
                    val catalogs = if (listCatalogs) listCatalogs(query) else null
                    if (catalogs != null) listedRoots += local.uniqueId
                    val exists = catalogs?.any { it.second.equals(catalog, ignoreCase = true) } ?: true
                    val databases = if (listDatabases && exists) listDatabases(query, catalog) else null
                    DorisCatalogs.info("namespace listing on '${local.name}': catalogs -> ${catalogs?.map { it.second } ?: "(known)"}" +
                        (databases?.let { "; catalog '$catalog' databases -> $it" } ?: ""))
                    Listing(catalogs, catalog.takeIf { databases != null }, databases)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DorisCatalogs.warn("namespace listing after console switch failed: ${e.message}")
                return@launch
            }
            val cleared = withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext false
                write(local, listing).also { DbPsiFacade.getInstance(project).clearCaches() }
            }
            // Every database of the catalog is listed now: the shared auto-introspection need not load it again.
            listing.catalog?.let { project.service<DorisAutoIntrospectService>().markLoaded(local, listOf(it)) }
            if (cleared) syncCleared(local)
        })
    }

    private fun claim(local: LocalDataSource, level: String): Boolean = asked.add("${local.uniqueId}|${level.lowercase()}")

    /**
     * Adds the listed names to the model; nothing is removed, and nothing below a database is touched. True
     * when [local] was cleared (no model yet, or a scope without a catalog level): its default sync is due.
     */
    internal fun write(local: LocalDataSource, listing: Listing): Boolean {
        var cleared = false
        if (local.model !is BasicModModel) {
            // The first names a cleared data source learns start its model, as an introspection would.
            local.model = com.intellij.database.model.ModelFactory.BLACK_HOLE.createModel(local.dbms)
            DorisCatalogs.info("model created for '${local.name}' to hold the listed names")
            cleared = true
        }
        if (normalizeScope(local)) cleared = true
        val model = local.model as? BasicModModel ?: return cleared
        val root = model.root as? MsRoot ?: return cleared
        DorisModelWrite.write(model) {
            listing.catalogs?.forEach { (id, name) ->
                if (root.databases.none { it.name.equals(name, ignoreCase = true) }) root.databases.createOrGet(id).name = name
            }
            val catalog: MsDatabase? = listing.catalog?.let { c -> root.databases.firstOrNull { it.name.equals(c, ignoreCase = true) } }
            listing.databases?.forEach { name ->
                if (catalog != null && catalog.schemas.none { it.name.equals(name, ignoreCase = true) }) catalog.schemas.createOrGet(name)
            }
        }
        return cleared
    }

    /**
     * The sync a cleared data source gets from the platform's Forget Model and Refresh, run once per session:
     * the platform's own general refresh under the (normalized) scope, so `internal` loads and the other
     * catalogs are listed without the user ticking anything. Waits for it to end; false when it did not run.
     */
    protected open suspend fun syncCleared(local: LocalDataSource): Boolean {
        if (!claim(local, "cleared sync")) return false
        val manager = com.intellij.database.dataSource.DataSourceSyncManager.getInstance()
        if (manager.isActive(local)) {
            DorisCatalogs.info("sync of cleared '${local.name}' not started: a refresh is already running")
            return false
        }
        DorisCatalogs.info("sync starts for cleared '${local.name}' (the platform's general refresh) under scope " +
            com.intellij.database.util.TreePatternUtils.serialize(local.introspectionScope))
        val context = com.intellij.database.util.LoaderContext.selectGeneralTask(project, local)
        val result = manager.tryPerformSync(context, stopRunning = false, merge = false)
        DorisCatalogs.info("sync of cleared '${local.name}' ${if (result != null && context.errorHandler?.hasErrors() != true) "done" else "ended with errors or did not run"}")
        return result != null
    }

    /**
     * An "All databases" scope with no catalog level (what a cleared schemas pane holds) is stored as the
     * default it means, `internal` deep and the other catalogs listed, so the scope the auto-introspection
     * widens later keeps them, and the data source tree shows them selected.
     */
    private fun normalizeScope(local: LocalDataSource): Boolean {
        val scope = local.introspectionScope
        if (scope.root?.getGroup(com.intellij.database.model.ObjectKind.DATABASE) != null) return false
        val normalized = DorisCatalogScopes.expandExplicitCatalogSelections(scope)
        if (normalized === scope) return false
        local.introspectionScope = normalized
        com.intellij.database.dataSource.DataSourceStorage.getProjectStorage(project)?.takeIf { local in it.dataSources }?.updateDataSource(local)
        DorisCatalogs.info("scope of '${local.name}' normalized: '${com.intellij.database.util.TreePatternUtils.serialize(scope)}' " +
            "(no catalog level) stored as the default '${com.intellij.database.util.TreePatternUtils.serialize(normalized)}'")
        return true
    }

    /**
     * Runs [use] with queries on a short-lived helper connection of [local]: its own session, so no console
     * transaction is touched. Each query answers its rows by column label.
     */
    protected open suspend fun <T> withQueries(local: LocalDataSource, use: (query: (String) -> List<Map<String, String?>>) -> T): T {
        val ref = DatabaseConnectionManager.getInstance().build(project, HelperConnectionPoint(local))
            .setRequestor(helperRequestor()).setAskPassword(false).create()
            ?: error("no connection to '${local.name}'")
        return ref.use { connection -> use { sql -> rows(connection.get(), sql) } }
    }

    private fun rows(connection: DatabaseConnectionCore, sql: String): List<Map<String, String?>> {
        val statement = JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() } ?: return emptyList()
        try {
            val result = JdbcNativeUtil.computeRemote { statement.executeQuery(sql) } ?: return emptyList()
            try {
                val meta = result.metaData
                val labels = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                val out = ArrayList<Map<String, String?>>()
                while (result.next()) out += labels.withIndex().associate { (i, label) -> label to result.getString(i + 1) }
                return out
            } finally {
                JdbcNativeUtil.performSafe { result.close() }
            }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }
}

/**
 * Databases whose tables a Doris introspection loaded whole. The shared auto-introspection cannot tell them
 * from databases it knows only partly (a default scope selects nothing by name), so each project's
 * [DorisAutoIntrospectService] marks them loaded; completing their path loads nothing again.
 */
object DorisListedNamespaces {
    fun record(dataSourceId: String, catalog: String, database: String) {
        for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val local = LocalDataSourceManager.getInstance(project).dataSources
                .firstOrNull { it.uniqueId == dataSourceId } ?: continue
            project.getService(DorisAutoIntrospectService::class.java)?.markLoaded(local, listOf(catalog, database))
        }
    }
}
