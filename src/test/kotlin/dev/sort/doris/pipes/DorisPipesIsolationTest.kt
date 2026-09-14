package dev.sort.doris.pipes

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil

/** Reflection intentionally avoids linking test-core copies of either product's implementation. */
class DorisPipesIsolationTest : BasePlatformTestCase() {
    fun testProductsOwnTheirEngineClassesAndTranspileIndependently() {
        if (System.getProperty("test.pluginIsolation") != "true") return
        val dorisLoader = PluginManagerCore.getPlugin(PluginId.getId("dev.sort.doris-intellij-plugin"))!!.pluginClassLoader!!
        assertEquals("com.intellij.ide.plugins.cl.PluginClassLoader", dorisLoader.javaClass.name)
        val settingsType = dorisLoader.loadClass("dev.sort.doris.pipes.DorisPipesSettings")
        val settings = project.getService(settingsType)!!
        assertEquals(false, settingsType.getMethod("getEnabled").invoke(settings))
        val setEnabled = settingsType.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val policy = dorisLoader.loadClass("dev.sort.doris.pipes.DorisPipes")
        val instance = policy.getField("INSTANCE").get(null)
        val isEnabled = policy.getMethod("isEnabled", Project::class.java)
        val action = ActionManager.getInstance().getAction("Console.Jdbc.Execute")!!
        assertSame(dorisLoader, action.javaClass.classLoader)
        try {
            setEnabled.invoke(settings, true)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(true, isEnabled.invoke(instance, project))
            checkEngine(dorisLoader, "jvmMain-0.14.0")
            val adapter = dorisLoader.loadClass("dev.sort.doris.pipes.DorisPipesEngine")
            val translated = adapter.getMethod("transpile", String::class.java)
                .invoke(adapter.getField("INSTANCE").get(null), "FROM t |> LIMIT 1")
            assertFalse((translated.javaClass.getMethod("getDorisSql").invoke(translated) as String).contains("|>"))

            val companion = PluginManagerCore.getPlugin(PluginId.getId("dev.sort.sql-transpiler-intellij-plugin"))
            if (System.getProperty("test.sqlTranspiler") == "installed") {
                assertNotNull(companion)
                val companionLoader = companion!!.pluginClassLoader!!
                assertEquals("com.intellij.ide.plugins.cl.PluginClassLoader", companionLoader.javaClass.name)
                assertNotSame(dorisLoader, companionLoader)
                checkEngine(companionLoader, "jvm-0.6.0")
                for (type in listOf("dev.brikk.house.sql.shape.SqlFragment", "dev.brikk.house.sql.metadata.FunctionDef")) {
                    assertNotSame(dorisLoader.loadClass(type), companionLoader.loadClass(type))
                }
            } else assertNull(companion)
            for (dialect in System.getProperty("test.siblingPlugins", "").split(',').filter { it.isNotBlank() }) {
                val peer = PluginManagerCore.getPlugin(PluginId.getId("dev.sort.$dialect-intellij-plugin"))!!
                assertEquals("com.intellij.ide.plugins.cl.PluginClassLoader", peer.pluginClassLoader!!.javaClass.name)
                assertNotSame(dorisLoader, peer.pluginClassLoader)
                // 262 can leave dialect languages uninitialized until their first use.
                val dialectClass = if (dialect == "trino") "dev.sort.trino.sql.TrinoSqlDialect"
                    else "dev.sort.duckdb.sql.DuckdbSqlDialect"
                val language = peer.pluginClassLoader!!.loadClass(dialectClass).getField("INSTANCE").get(null) as Language
                assertSame(language, Language.findLanguageByID(language.id))
                val file = PsiFileFactory.getInstance(project).createFileFromText("$dialect.sql", language, "SELECT 1;")
                assertEquals("SELECT 1;", file.node.text)
            }
        } finally {
            setEnabled.invoke(settings, false)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        assertEquals(false, isEnabled.invoke(instance, project))
        assertSame(action, ActionManager.getInstance().getAction("Console.Jdbc.Execute"))
    }

    private fun checkEngine(loader: ClassLoader, artifactSuffix: String) {
        val type = loader.loadClass("dev.brikk.house.sql.shape.SqlFragment")
        assertSame(loader, type.classLoader)
        assertTrue(type.getResource("SqlFragment.class").toString().contains("brikk-sql-$artifactSuffix.jar"))
        val metadata = loader.loadClass("dev.brikk.house.sql.metadata.FunctionDef")
        assertSame(loader, metadata.classLoader)
        assertTrue(metadata.getResource("FunctionDef.class").toString().contains("brikk-sql-metadata-$artifactSuffix.jar"))
        val fragment = type.getConstructor(String::class.java, String::class.java)
            .newInstance("FROM t |> SELECT a |> LIMIT 1", "doris")
        val result = type.getMethod("toExecutable", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(fragment, "doris", true, true)
        assertSame(loader, result.javaClass.classLoader)
        val sql = result.javaClass.getMethod("getSql").invoke(result) as String
        assertTrue(sql.contains("LIMIT 1"))
        assertFalse(sql.contains("|>"))
    }
}
