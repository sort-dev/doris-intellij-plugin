package dev.sort.doris.pipes;

import com.intellij.database.introspection.IntrospectionTask;
import com.intellij.database.introspection.IntrospectionTasks;
import com.intellij.database.model.basic.BasicElement;

/**
 * DORIS PIPES: Java shim for {@link IntrospectionTasks} — the factory is Kotlin-{@code internal}
 * in metadata (public in bytecode), so Kotlin sources cannot reference it while Java can. Used by
 * DorisPipesAutoIntrospect to build the TARGETED one-element refresh (never a general sync).
 */
public final class PipeIntrospectionTasks {
    private PipeIntrospectionTasks() {}

    public static IntrospectionTask oneElementRefresh(String dataSourceId, BasicElement element) {
        return IntrospectionTasks.prepareOneElementRefreshTask(dataSourceId, element);
    }
}
