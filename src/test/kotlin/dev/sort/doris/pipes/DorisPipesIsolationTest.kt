package dev.sort.doris.pipes

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil

/** Reflection intentionally avoids linking test-core copies of either product's implementation. */
class DorisPipesIsolationTest : BasePlatformTestCase() {
    fun testProductsOwnTheirEngineClassesAndTranspileIndependently() {
        if (System.getProperty("test.pluginIsolation") != "true") return
        assertEquals(setOf("trino", "duckdb", "clickhouse"), System.getProperty("test.siblingPlugins", "").split(',').toSet())
        assertEquals("installed", System.getProperty("test.sqlTranspiler"))
        val dorisLoader = PluginManagerCore.getPlugin(PluginId.getId("dev.sort.doris-intellij-plugin"))!!.pluginClassLoader!!
        assertEquals("com.intellij.ide.plugins.cl.PluginClassLoader", dorisLoader.javaClass.name)
        assertEquals("Optional Kotlin loading must match the selected lane", System.getProperty("test.kotlin") == "present",
            PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.kotlin"))?.isEnabled == true)
        for (name in listOf("CooperativeExecuteAction", "TranslatingScriptModel", "SqlColumnCompletion")) {
            val shared = dorisLoader.loadClass("dev.brikk.house.sql.intellij.$name")
            assertSame(dorisLoader, shared.classLoader)
            assertTrue(shared.getResource("$name.class").toString().contains("brikk-sql-intellij-support-0.2.0-SNAPSHOT.jar"))
        }
        for (name in listOf("completion.SqlCompletionContributor", "completion.SqlCompletionConfidence",
            "completion.SqlBuiltinPrototypeFilter", "psi.SelectAliasResolveExtension", "catalog.DialectDataSourceResolver",
            "catalog.CatalogProfile", "catalog.NamespacePathResolver", "catalog.CatalogScopes", "catalog.AutoIntrospectEngine",
            "catalog.AutoIntrospectService", "catalog.IntrospectBannerProvider", "catalog.QualifiedPathIntrospectContributor",
            "catalog.OutOfScopeReferenceFilter", "catalog.IntrospectNamespaceIntention", "catalog.CatalogImports")) {
            val shared = dorisLoader.loadClass("dev.brikk.house.sql.intellij.$name")
            assertSame(dorisLoader, shared.classLoader)
            assertTrue(shared.getResource("${shared.simpleName}.class").toString().contains("brikk-sql-intellij-support-0.2.0-SNAPSHOT.jar"))
        }
        val verifier = dorisLoader.loadClass("dev.brikk.house.sql.verify.DorisVerifier")
        assertSame(dorisLoader, verifier.classLoader)
        assertTrue(verifier.getResource("DorisVerifier.class").toString().contains("brikk-sql-verify-doris-0.20.0-SNAPSHOT.jar"))
        val verifyResult = dorisLoader.loadClass("dev.brikk.house.sql.verify.VerifyResult")
        assertSame(dorisLoader, verifyResult.classLoader)
        assertTrue(verifyResult.getResource("VerifyResult.class").toString().contains("brikk-sql-verify-api-0.20.0-SNAPSHOT.jar"))
        val factory = verifier.getField("Companion").get(null)
        val native = factory.javaClass.getMethod("create", java.nio.file.Path::class.java).invoke(factory, null)
        val checkCanceled: () -> Unit = com.intellij.openapi.progress.ProgressManager::checkCanceled
        val verified = verifier.getMethod("verifyScript", String::class.java, Function0::class.java)
            .invoke(native, "SELECT 1; SELECT 2", checkCanceled)
        assertEquals("Accepted", verified.javaClass.simpleName)
        // Copied helper contracts may load in DataGrip; Kotlin-aware helper entry points must not.
        val families = dorisLoader.loadClass("dev.brikk.house.intellij.BrikkTypeFamilies")
        assertSame(dorisLoader, families.classLoader)
        assertTrue(families.getResource("BrikkTypeFamilies.class").toString().contains("brikk-engine-kotlin-intellij-support-0.2.0.jar"))
        val family = families.getMethod("forClassId", String::class.java).invoke(families.getField("INSTANCE").get(null), "kotlin.String")
        assertEquals("TEXT", family.toString())
        val settingsType = dorisLoader.loadClass("dev.sort.doris.pipes.DorisPipesSettings")
        val settings = project.getService(settingsType)!!
        val introspectionType = dorisLoader.loadClass("dev.sort.doris.catalog.DorisAutoIntrospectService")
        assertSame(dorisLoader, project.getService(introspectionType)!!.javaClass.classLoader)
        assertSame(dorisLoader.loadClass("dev.brikk.house.sql.intellij.catalog.AutoIntrospectService"), introspectionType.superclass)
        assertEquals(false, settingsType.getMethod("getEnabled").invoke(settings))
        val setEnabled = settingsType.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val policy = dorisLoader.loadClass("dev.sort.doris.pipes.DorisPipes")
        val instance = policy.getField("INSTANCE").get(null)
        val isEnabled = policy.getMethod("isEnabled", Project::class.java)
        val action = ActionManager.getInstance().getAction("Console.Jdbc.Execute")!!
        assertTrue(actionChain(action).any { it.javaClass.classLoader === dorisLoader })
        try {
            setEnabled.invoke(settings, true)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(true, isEnabled.invoke(instance, project))
            checkEngine(dorisLoader, "jvmMain-0.20.0-SNAPSHOT")
            val profile = dorisLoader.loadClass("dev.sort.doris.pipes.DorisPipeDialect").getField("INSTANCE").get(null)
            val profileType = dorisLoader.loadClass("dev.brikk.house.sql.intellij.pipe.PipeDialect")
            assertSame(dorisLoader, profileType.classLoader)
            val lowering = dorisLoader.loadClass("dev.brikk.house.sql.intellij.pipe.PipeEngineKt")
            val translated = lowering.getMethod("lower", profileType, String::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, com.intellij.psi.PsiFile::class.java)
                .invoke(null, profile, "FROM t |> LIMIT 1", false, false, null)
            assertFalse((translated.javaClass.getMethod("getSql").invoke(translated) as String).contains("|>"))

            val companion = PluginManagerCore.getPlugin(PluginId.getId("dev.sort.sql-transpiler-intellij-plugin"))
            if (System.getProperty("test.sqlTranspiler") == "installed") {
                assertNotNull(companion)
                val companionLoader = companion!!.pluginClassLoader!!
                assertEquals("com.intellij.ide.plugins.cl.PluginClassLoader", companionLoader.javaClass.name)
                assertNotSame(dorisLoader, companionLoader)
                val source = companionLoader.loadClass("dev.brikk.house.sql.shape.SqlFragment").getResource("SqlFragment.class").toString()
                assertTrue(source, source.contains("brikk-sql-") && source.contains(".jar"))
                checkEngine(companionLoader, source.substringAfterLast("brikk-sql-").substringBefore(".jar"))
                for (type in listOf("dev.brikk.house.sql.shape.SqlFragment", "dev.brikk.house.sql.metadata.FunctionDef")) {
                    assertNotSame(dorisLoader.loadClass(type), companionLoader.loadClass(type))
                }
            } else assertNull(companion)
            for (dialect in System.getProperty("test.siblingPlugins", "").split(',').filter { it.isNotBlank() }) {
                val peer = PluginManagerCore.getPlugin(PluginId.getId("dev.sort.$dialect-intellij-plugin"))!!
                assertEquals("com.intellij.ide.plugins.cl.PluginClassLoader", peer.pluginClassLoader!!.javaClass.name)
                assertNotSame(dorisLoader, peer.pluginClassLoader)
                val peerShared = runCatching { peer.pluginClassLoader!!.loadClass("dev.brikk.house.sql.intellij.CooperativeExecuteAction") }.getOrNull()
                if (peerShared != null) assertNotSame(dorisLoader.loadClass(peerShared.name), peerShared)
                // 262 can leave dialect languages uninitialized until their first use.
                val dialectClass = when (dialect) {
                    "trino" -> "dev.sort.trino.sql.TrinoSqlDialect"
                    "duckdb" -> "dev.sort.duckdb.sql.DuckdbSqlDialect"
                    "clickhouse" -> "dev.sort.clickhouse.sql.ClickHouseDialect"
                    else -> error("Unexpected sibling $dialect")
                }
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

    fun testActualFourDialectRegistrationsInAllTwentyFourOrders() {
        if (System.getProperty("test.pluginIsolation") != "true") return
        val names = mapOf("doris" to "dev.sort.doris.pipes.DorisPipesActionConfiguration",
            "trino" to "dev.sort.trino.pipes.TrinoPipesActionConfiguration",
            "duckdb" to "dev.sort.duckdb.pipes.DuckdbPipesActionConfiguration",
            "clickhouse" to "dev.sort.clickhouse.pipe.ClickHouseExecuteConfiguration")
        val loaders = names.keys.associateWith {
            PluginManagerCore.getPlugin(PluginId.getId("dev.sort.$it-intellij-plugin"))?.pluginClassLoader
        }
        assertTrue("All four dialect distributions are required", loaders.values.all { it != null })
        val ids = listOf("Console.Jdbc.Execute", "Console.Jdbc.Execute.2", "Console.Jdbc.Execute.3", "Console.Jdbc.Execute.Selection")
        val manager = ActionManager.getInstance()
        val originals = ids.associateWith { manager.getAction(it)!! }
        fun orders(remaining: List<String>): List<List<String>> = if (remaining.isEmpty()) listOf(emptyList())
            else remaining.flatMap { first -> orders(remaining - first).map { listOf(first) + it } }
        val orders = orders(names.keys.toList())
        assertEquals(24, orders.size)
        try {
            for (order in orders) {
                var delegated = 0
                val event = AnActionEvent.createFromDataContext("Peer chain", Presentation(), SimpleDataContext.getProjectContext(project))
                ids.forEach { manager.replaceAction(it, object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) { assertSame(event, e); delegated++ }
                }) }
                val configurations = order.map { loaders.getValue(it)!!.loadClass(names.getValue(it)).getConstructor().newInstance()
                    as DynamicActionConfigurationCustomizer }
                try {
                    configurations.forEach { it.registerActions(manager) }
                    for (id in ids) {
                        val chain = actionChain(manager.getAction(id)!!)
                        assertEquals("All product loaders must appear in $order for $id", order.reversed().map { loaders.getValue(it) },
                            chain.dropLast(1).map { it.javaClass.classLoader })
                        manager.getAction(id)!!.actionPerformed(event)
                    }
                    assertEquals("Each variant delegates exactly once through $order", 4, delegated)
                    configurations.first().unregisterActions(manager)
                    ids.forEach { manager.getAction(it)!!.actionPerformed(event) }
                    assertEquals("Detached predecessor remains usable through $order", 8, delegated)
                } finally { configurations.asReversed().forEach { it.unregisterActions(manager) } }
            }
        } finally { originals.forEach { (id, action) -> manager.replaceAction(id, action) } }
    }

    private fun actionChain(first: AnAction): List<AnAction> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AnAction, Boolean>())
        val chain = mutableListOf<AnAction>()
        var current = first
        while (seen.add(current)) {
            chain.add(current)
            current = (current as? ActionWithDelegate<*>)?.delegate as? AnAction ?: break
        }
        return chain
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
