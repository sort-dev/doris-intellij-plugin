package dev.sort.doris.pipes

import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.LoaderContext
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternNode
import com.intellij.database.util.TreePatternUtils
import com.intellij.openapi.project.Project

/**
 * DORIS PIPES (user call-out): when a pipe statement references a RESOLVED-but-childless schema
 * (enumerated but never introspected — typo-proof by construction), don't ask the user to go
 * click "introspect" — do it for them: widen the data source's introspection scope to exactly
 * that catalog.schema (same TreePattern vocabulary as [dev.sort.doris.catalog.DorisCatalogScopes];
 * `union` never clobbers the user's own selections) and kick the platform sync. One shot per
 * (data source, namespace) per IDE session — a failed/empty introspection never loops.
 */
object DorisPipesAutoIntrospect {

    private val requested = java.util.Collections.synchronizedSet(HashSet<String>())

    /** True if a NEW auto-introspection was kicked off; false = already requested this session. */
    fun request(
        project: Project,
        local: LocalDataSource,
        catalog: String?,
        schema: String,
        node: Any? = null,
    ): Boolean {
        val key = "${local.uniqueId}|${catalog ?: ""}.$schema"
        if (!requested.add(key)) return false
        return runCatching {
            val schemaNode = TreePatternNode(
                TreePatternNode.PositiveNaming(ObjectName.plain(schema)),
                TreePatternNode.NO_GROUPS,
            )
            val addition = if (catalog != null) {
                TreePattern(
                    TreePatternNode.Group(
                        ObjectKind.DATABASE,
                        arrayOf(
                            TreePatternNode(
                                TreePatternNode.PositiveNaming(ObjectName.plain(catalog)),
                                arrayOf(TreePatternNode.Group(ObjectKind.SCHEMA, arrayOf(schemaNode))),
                            ),
                        ),
                    ),
                )
            } else {
                TreePattern(TreePatternNode.Group(ObjectKind.SCHEMA, arrayOf(schemaNode)))
            }
            val current = local.introspectionScope
            local.introspectionScope =
                if (current == null) addition else TreePatternUtils.union(current, addition)
            // TARGETED task only (dogfood round 18: selectGeneralTask re-introspected the ENTIRE
            // widened scope — every catalog, hanging on slow externals). One element, one refresh.
            val element = node as? com.intellij.database.model.basic.BasicElement
            if (element == null) {
                DorisPipes.info("auto-introspect: scope widened for $key; node not a BasicElement — no sync kicked")
                return@runCatching false
            }
            val task = PipeIntrospectionTasks.oneElementRefresh(local.uniqueId, element)
            DataSourceSyncManager.getInstance()
                .tryPerform(LoaderContext.selectTask(project, local, task), true, false)
            DorisPipes.info("auto-introspect: scope widened + TARGETED refresh for $key")
            true
        }.getOrElse { t ->
            DorisPipes.warn("auto-introspect failed for $key: ${t.message}", t)
            false
        }
    }
}
