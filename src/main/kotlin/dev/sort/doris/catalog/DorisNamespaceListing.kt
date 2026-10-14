package dev.sort.doris.catalog

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dialects.mssql.model.MsDatabase
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint
import dev.brikk.house.sql.intellij.catalog.helperRequestor
import dev.brikk.house.sql.intellij.catalog.pathResolver
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisStringUtils
import dev.sort.doris.sql.dorisDataSourceResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The cheap namespace listing an empty or cleared model needs before targeted introspection can start:
 * a path whose first name the model does not list asks once for the catalog names (`SHOW CATALOGS`) and
 * the databases of the catalog it names (`SHOW DATABASES FROM c`), on a helper connection of its own (never
 * the console's session or transaction, never the EDT), writing only those names into the model. Then the
 * shared auto-introspection loads the targeted level as usual. A name still unknown after its listing stays
 * unknown: each data source lists each level at most once per session.
 */
open class DorisNamespaceListing(private val project: Project, private val scope: CoroutineScope) {
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** The names a listing found: catalog names, then the databases of [catalog] when it was listed. */
    class Listing(val catalogs: List<Pair<Long, String>>?, val catalog: String?, val databases: List<String>?)

    /**
     * Lists what [path] (as written in [file], relative to the console's namespace) needs when the model
     * does not know its first name; then asks the auto-introspection service for [path]. True when a
     * listing was queued. Call under read access; it never blocks.
     */
    fun ensure(file: PsiFile, path: List<String>): Boolean {
        if (!DorisCatalogs.enabled || path.isEmpty()) return false
        val local = dorisDataSourceResolver.resolve(file).dataSource ?: return false
        val roots = DbPsiFacade.getInstance(project).findDataSource(local.uniqueId)?.model?.modelRoots?.toList() ?: return false
        val current = dorisDataSourceResolver.consoleNamespace(file)
        if (DorisCatalogProfile.pathResolver.resolve(roots, path.take(1), current) != null) return false
        // A cleared or never-introspected data source has no model yet: nothing is known.
        val root = (local.model as? BasicModModel)?.root as? MsRoot
        val first = path.first()
        val namesCatalog = root?.databases?.any { it.name.equals(first, ignoreCase = true) } == true
        val fallbackCatalog = current.firstOrNull() ?: DorisCatalogScopes.INTERNAL_CATALOG
        val listCatalogs = !namesCatalog && claim(local, "catalogs")
        // Without a catalog listing the catalog is decided now; with one, after it (the path may name a catalog).
        val knownCatalog = if (namesCatalog) first else fallbackCatalog
        val databasesNeeded = listCatalogs || needsDatabases(root, knownCatalog)
        if (!listCatalogs && !(databasesNeeded && claim(local, "databases of $knownCatalog"))) return false
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(file)
        val shown = path.joinToString(".")
        DorisCatalogs.info("namespace listing for '$shown' on '${local.name}': catalogs ${if (listCatalogs) "listing" else "known"}")
        track(local, scope.launch(Dispatchers.IO) {
            val listing = try {
                withQueries(local) { query ->
                    val catalogs = if (listCatalogs) listCatalogs(query) else null
                    val catalog = when {
                        namesCatalog -> first
                        catalogs?.any { it.second.equals(first, ignoreCase = true) } == true -> catalogs.first { it.second.equals(first, ignoreCase = true) }.second
                        else -> fallbackCatalog
                    }
                    val known = catalogs?.any { it.second.equals(catalog, ignoreCase = true) } ?: true
                    val databases = if (known && (!listCatalogs || claim(local, "databases of $catalog")) &&
                        needsDatabases((local.model as? BasicModModel)?.root as? MsRoot, catalog)) listDatabases(query, catalog) else null
                    DorisCatalogs.info("namespace listing on '${local.name}': catalogs -> ${catalogs?.map { it.second } ?: "(known)"}" +
                        (databases?.let { "; catalog '$catalog' databases -> $it" } ?: ""))
                    Listing(catalogs, catalog.takeIf { databases != null }, databases)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DorisCatalogs.warn("namespace listing for '$shown' failed: ${e.message}")
                return@launch
            }
            val cleared = withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext false
                write(local, listing).also {
                    DbPsiFacade.getInstance(project).clearCaches()
                    pointer.element?.let { DaemonCodeAnalyzer.getInstance(project).restart(it, "Doris namespace listing") }
                }
            }
            if (cleared) syncCleared(local)
            readAction {
                val target = pointer.element ?: return@readAction
                val queued = project.service<DorisAutoIntrospectService>().requestPath(target, path)
                DorisCatalogs.info("namespace listing done for '$shown'; targeted introspection ${if (queued) "queued" else "not needed"}")
            }
        })
        return true
    }

    private fun track(local: LocalDataSource, job: kotlinx.coroutines.Job) {
        running[local.uniqueId] = job
        job.invokeOnCompletion { running.remove(local.uniqueId, job) }
    }

    /**
     * The shared auto-introspection's top-level listing ([DorisAutoIntrospectService.listNamespaces]): the
     * catalog names on a helper connection, written into the model, then a cleared data source's default
     * scope and general refresh, all before the service loads any level. When a listing of this data source
     * is already running (a completion or reference asked first), waits for it instead of listing again.
     */
    suspend fun listRoots(local: LocalDataSource): Boolean {
        if (!claim(local, "catalogs")) {
            running[local.uniqueId]?.join()
            return true
        }
        DorisCatalogs.info("namespace listing of the catalogs of '${local.name}' for the auto-introspection")
        val catalogs = try {
            withQueries(local) { query -> listCatalogs(query) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DorisCatalogs.warn("namespace listing of the catalogs of '${local.name}' failed: ${e.message}")
            asked.remove("${local.uniqueId}|catalogs")
            return false
        }
        DorisCatalogs.info("namespace listing on '${local.name}': catalogs -> ${catalogs.map { it.second }}")
        val cleared = withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext false
            write(local, Listing(catalogs, null, null)).also { DbPsiFacade.getInstance(project).clearCaches() }
        }
        if (cleared) syncCleared(local)
        return true
    }

    private fun needsDatabases(root: MsRoot?, catalog: String): Boolean =
        root?.databases?.firstOrNull { it.name.equals(catalog, ignoreCase = true) }?.schemas?.isEmpty ?: true

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
        // Every database of the catalog is listed now: the shared auto-introspection need not load it again.
        listing.catalog?.let { DorisListedNamespaces.complete(project, local.uniqueId, listOf(it)) }
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
 * Namespaces whose children Doris listed completely: a catalog whose databases a listing wrote, a database
 * whose tables an introspection loaded. The shared auto-introspection cannot tell them from namespaces it
 * knows only partly (a default scope selects nothing by name), so they are marked loaded in each project's
 * [DorisAutoIntrospectService]; completing their path loads nothing again.
 */
object DorisListedNamespaces {
    fun record(dataSourceId: String, catalog: String, database: String) {
        for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) complete(project, dataSourceId, listOf(catalog, database))
        }
    }

    fun complete(project: Project, dataSourceId: String, path: List<String>) {
        val engine = project.getService(DorisAutoIntrospectService::class.java)?.engine ?: return
        val key = dev.brikk.house.sql.intellij.catalog.AutoIntrospectEngine.Key(dataSourceId, path)
        // Not while a refresh of it runs: that one settles it.
        if (engine.state(key) == dev.brikk.house.sql.intellij.catalog.AutoIntrospectEngine.State.Done || !engine.claim(key)) return
        engine.finish(key, true)
    }
}

/**
 * Completion after a qualified path (`catalog.`, `catalog.db.`, `db.`): from an empty or cleared model,
 * the names the path needs are listed first ([DorisNamespaceListing]); the shared contributor then loads
 * the level. Offers nothing itself.
 */
class DorisNamespaceListingContributor : com.intellij.codeInsight.completion.CompletionContributor(), com.intellij.openapi.project.DumbAware {
    override fun fillCompletionVariants(parameters: com.intellij.codeInsight.completion.CompletionParameters, result: com.intellij.codeInsight.completion.CompletionResultSet) {
        if (!DorisCatalogs.enabled || qualifiesAStatementName(parameters.position)) return
        val file = parameters.originalFile
        val text = parameters.editor.document.charsSequence
        val start = (parameters.offset - 400).coerceAtLeast(0)
        val path = PATH_BEFORE_CARET.find(text.subSequence(start, parameters.offset))?.groupValues?.get(1)
            ?.split('.')?.map { it.trim().removeSurrounding("`") }?.filter { it.isNotEmpty() } ?: return
        if (path.isEmpty()) return
        val project = file.project
        project.service<DorisNamespaceListing>().ensure(file, path)
    }

    private companion object {
        /** A dotted path ending just before the caret (optionally followed by the name being typed). */
        val PATH_BEFORE_CARET = Regex("""((?:(?:`[^`]*`|[A-Za-z_][\w$]*)\s*\.\s*)+)(?:[A-Za-z_][\w$]*)?$""")
    }
}
