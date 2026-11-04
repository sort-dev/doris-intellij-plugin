package dev.sort.doris.catalog

import com.intellij.database.layoutedQueries.DBTransaction
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.remote.jdba.core.Layouts
import com.intellij.database.remote.jdba.sql.SqlQuery
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import dev.sort.doris.DorisDefinitionProvider
import java.util.WeakHashMap

/**
 * Ms tables cannot store source text. Retain the server text in the IDE's non-roaming project
 * cache, keyed by data-source ID and full object identity, so it survives model replacement and
 * IDE restarts. Renderers only read the cache; all JDBC work belongs to the introspector.
 */
@Service(Service.Level.PROJECT)
@State(name = "DorisOriginalDefinitions", storages = [Storage(StoragePathMacros.CACHE_FILE)])
internal class DorisOriginalDefinitions(private val project: Project) : PersistentStateComponent<DorisOriginalDefinitions.Sources> {
    class Sources {
        var definitions: MutableMap<String, String> = HashMap()
    }

    private var sources = Sources()
    private val dataSources = WeakHashMap<BasicModel, String>()

    @Synchronized
    override fun getState(): Sources = Sources().also { it.definitions.putAll(sources.definitions) }

    @Synchronized
    override fun loadState(state: Sources) {
        sources = Sources().also { it.definitions.putAll(state.definitions) }
    }

    @Synchronized
    fun bind(model: BasicModel, dataSourceId: String) {
        dataSources[model] = dataSourceId
    }

    private fun key(element: BasicElement): String? {
        val id = synchronized(this) { dataSources[element.model] }
            ?: DbPsiFacade.getInstance(project).findElement(element)?.dataSource?.uniqueId
            ?: return null
        // Length-prefix the source ID rather than relying on a delimiter absent from identifiers.
        return "${id.length}:$id${element.identity()}"
    }

    fun get(element: BasicElement): String? {
        val key = key(element) ?: return null
        return synchronized(this) { sources.definitions[key] }
    }

    fun put(element: BasicElement, ddl: String) {
        val key = checkNotNull(key(element)) { "No data source for ${element.identity()}" }
        synchronized(this) { sources.definitions[key] = ddl }
    }

    fun forget(element: BasicElement) {
        val key = key(element) ?: return
        synchronized(this) { sources.definitions.remove(key) }
    }

    companion object {
        fun read(transaction: DBTransaction, element: BasicElement): String {
            val query = checkNotNull(DorisDefinitionProvider().query(element)) {
                "No original definition query for ${element.identity()}"
            }
            // Doris SHOW CREATE TABLE / VIEW returns the name, then the original CREATE text.
            val row = transaction.query(SqlQuery(query.sql, Layouts.rowOf(Layouts.rawArray()))).use { it.run() }
            return (row?.getOrNull(1) as? String)?.takeIf { it.isNotBlank() }
                ?: error("${query.sql} returned no CREATE text")
        }
    }
}
