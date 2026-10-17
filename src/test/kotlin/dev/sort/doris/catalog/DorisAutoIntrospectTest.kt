package dev.sort.doris.catalog

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.dialects.mysqlbase.model.MysqlBaseRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternUtils
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.SqlBundle
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectEngine
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.catalog.pathResolver
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.HeldIntrospections
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.pipes.DorisPipesSettings
import dev.sort.doris.sql.DorisSqlDialect
import dev.sort.doris.sql.DorisHighlightInfoFilter
import dev.sort.doris.sql.DORIS_SERVER_DIAGNOSTIC_PREFIX
import java.util.concurrent.CancellationException

class DorisAutoIntrospectTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var introspections: HeldIntrospections
    private lateinit var source: LocalDataSource
    private lateinit var requests: DorisAutoIntrospectService
    private var originalFlag: String? = null

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        introspections = HeldIntrospections(testRootDisposable)
        originalFlag = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
        source = LocalDataSource().apply {
            name = "Offline Doris auto-introspection"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:doris-auto-introspection"
            isAutoSynchronize = false
            isKeepAlive = false
            model = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
            introspectionScope = DorisCatalogScopes.multiCatalogDefaultScope()
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        requests = DorisAutoIntrospectService(project, errors.newScope(testRootDisposable))
        project.registerOrReplaceServiceInstance(DorisAutoIntrospectService::class.java, requests, testRootDisposable)
        configure("SELECT 1<caret>")
    }

    override fun tearDown() {
        RunAll(
            { introspections.finishAll() },
            { errors.awaitScopes() },
            { errors.assertNone() },
            { setSqlDialectMapping(project, null, null) },
            { SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile, null) },
            { LocalDataSourceManager.getInstance(project).removeDataSource(source) },
            { DbPsiFacade.getInstance(project).clearCaches() },
            { originalFlag?.let { System.setProperty(DorisCatalogs.PROPERTY, it) } ?: System.clearProperty(DorisCatalogs.PROPERTY) },
            { super.tearDown() },
        ).run()
    }

    fun testCatalogFlagOnSelectsTheSchemaAndPreservesInternalScope() {
        val schema = catalog("warehouse").schemas.createOrGet("target_db")
        assertEquals(listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA), DorisCatalogProfile.levels)
        assertTrue(requests.requestPath(myFixture.file, listOf("warehouse", "target_db")))
        val refresh = introspections.next()
        assertEquals(1, refresh.context.countTasks())
        assertEquals("warehouse", refresh.context.tasks.single().databaseName)
        val scope = TreePatternUtils.serialize(source.introspectionScope)
        assertTrue(scope, "internal" in scope && "warehouse" in scope && "target_db" in scope)
        refresh.finish { schema.tables.createOrGet("events") }
        awaitDone(listOf("warehouse", "target_db"))
    }

    fun testCatalogSchemaTableResolvesThroughSharedImports() {
        val table = catalog("warehouse").schemas.createOrGet("analytics").tables.createOrGet("events")
        source.introspectionScope = CatalogScopes.select(DorisCatalogProfile.levels, listOf("warehouse", "analytics"))
        DbPsiFacade.getInstance(project).clearCaches()
        configure("SELECT * FROM warehouse.analytics.<caret>events")
        assertSame(table, (reference().resolve() as com.intellij.database.psi.DbElement).delegate)
        introspections.assertNone()
    }

    fun testSharedFilterSuppressesOnlyOutOfScopePlatformReports() {
        catalog("warehouse").schemas.createOrGet("analytics")
        configure("SELECT * FROM warehouse.analytics.<caret>events")
        val reference = reference()
        val unresolved = report(reference, SqlBundle.message("unable.to.resolve.symbol.0.1", "table", "events"))
        assertFalse(DorisOutOfScopeReferenceFilter().accept(unresolved, myFixture.file))
        // 1.5.8's own out-of-scope rule hides it too; it also covers columns read through derived tables.
        assertFalse(DorisHighlightInfoFilter().accept(unresolved, myFixture.file))
        val server = report(reference, "$DORIS_SERVER_DIAGNOSTIC_PREFIX Unable to resolve table 'events'")
        assertTrue(DorisOutOfScopeReferenceFilter().accept(server, myFixture.file))
        assertTrue(DorisHighlightInfoFilter().accept(server, myFixture.file))
        System.setProperty(DorisCatalogs.PROPERTY, "false")
        assertFalse(DorisCatalogProfile.outOfScopeReferences)
        assertTrue(DorisOutOfScopeReferenceFilter().accept(unresolved, myFixture.file))
    }

    fun testSharedFilterKeepsMissingNamesUnderIntrospectedNamespaces() {
        catalog("warehouse").schemas.createOrGet("analytics").tables.createOrGet("loaded")
        configure("SELECT * FROM warehouse.analytics.<caret>missing")
        assertTrue(DorisOutOfScopeReferenceFilter().accept(report(reference(),
            SqlBundle.message("unable.to.resolve.symbol.0.1", "table", "missing")), myFixture.file))
    }

    fun testSharedIntentionIntrospectsTheUnloadedNamespace() {
        val schema = catalog("warehouse").schemas.createOrGet("analytics")
        configure("SELECT * FROM warehouse.analytics.<caret>events")
        val intention = DorisIntrospectNamespaceIntention()
        assertEquals("Doris: introspect namespace", intention.familyName)
        assertTrue(intention.isAvailable(project, myFixture.editor, reference()))
        intention.invoke(project, myFixture.editor, reference())
        assertFalse(intention.isAvailable(project, myFixture.editor, reference()))
        introspections.next().finish { schema.tables.createOrGet("events") }
        awaitDone(listOf("warehouse", "analytics"))
        assertFalse(intention.isAvailable(project, myFixture.editor, reference()))
    }

    private fun reference(): SqlReferenceExpression = checkNotNull(PsiTreeUtil.getNonStrictParentOfType(
        myFixture.file.findElementAt(myFixture.caretOffset), SqlReferenceExpression::class.java))

    private fun report(reference: SqlReferenceExpression, message: String): HighlightInfo =
        checkNotNull(HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
            .range(reference.identifier!!.textRange).descriptionAndTooltip(message).create())

    fun testCatalogFlagOffUsesOneSchemaLevel() {
        System.setProperty(DorisCatalogs.PROPERTY, "false")
        source.model = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
        val schema = ((source.model as BasicModModel).root as MysqlBaseRoot).schemas.createOrGet("analytics")
        DbPsiFacade.getInstance(project).clearCaches()
        assertEquals(listOf(ObjectKind.SCHEMA), DorisCatalogProfile.levels)
        assertTrue(requests.requestPath(myFixture.file, listOf("analytics")))
        val refresh = introspections.next()
        assertNotNull(source.introspectionScope.root.getGroup(ObjectKind.SCHEMA))
        assertEquals("analytics", requests.pending(myFixture.file.virtualFile))
        refresh.finish { schema.tables.createOrGet("events") }
        awaitDone(listOf("analytics"))
    }

    fun testDepthThreeReachesATableButNotAColumn() {
        val table = catalog("warehouse").schemas.createOrGet("analytics").tables.createOrGet("events")
        assertEquals(3, DorisCatalogProfile.maxDeepenDepth)
        assertTrue(requests.requestPath(myFixture.file, listOf("warehouse", "analytics", "events")))
        val refresh = introspections.next()
        refresh.finish { table.columns.createOrGet("id") }
        awaitDone(listOf("warehouse", "analytics", "events"))
        assertNull(DorisCatalogProfile.pathResolver.resolve(source.model.modelRoots.toList(), listOf("warehouse", "analytics", "events", "id")))
    }

    fun testBacktickQualifiedCompletionUsesTheRegisteredServiceAndBanner() {
        val quoted = catalog("ware`house")
        configure("SELECT * FROM `ware``house`.<caret>")
        myFixture.completeBasic()
        val refresh = introspections.next()
        assertEquals("ware`house", requests.pending(myFixture.file.virtualFile))
        assertNotNull(DorisIntrospectBannerProvider().collectNotificationData(project, myFixture.file.virtualFile))
        refresh.finish { quoted.schemas.createOrGet("analytics") }
        awaitDone(listOf("ware`house"))
        assertNull(DorisIntrospectBannerProvider().collectNotificationData(project, myFixture.file.virtualFile))
    }

    fun testCurrentNamespaceResolvesBareAndSchemaQualifiedTables() {
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            recording.console.dataSource.model = source.model
            val table = catalog("warehouse").schemas.createOrGet("analytics").tables.createOrGet("events")
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("warehouse", ObjectKind.DATABASE).append("analytics", ObjectKind.SCHEMA)))
            DbPsiFacade.getInstance(project).clearCaches()
            val roots = source.model.modelRoots.toList()
            val namespace = DorisCatalogProfile.dataSources.consoleNamespace(myFixture.file)
            for (path in listOf(listOf("events"), listOf("analytics", "events"))) {
                val target = DorisCatalogProfile.pathResolver.resolve(roots, path, namespace)
                assertSame(table, target?.node)
                assertEquals(listOf("warehouse", "analytics", "events"), target?.path)
            }
            assertTrue(requests.requestPath(myFixture.file, listOf("events")))
            introspections.next().finish { table.columns.createOrGet("id") }
            awaitDone(listOf("warehouse", "analytics", "events"), recording.console.dataSource.uniqueId)
        }
    }

    fun testPipeStageHeadHidesLoadedColumns() {
        catalog("warehouse").schemas.createOrGet("analytics").tables.createOrGet("events").columns.createOrGet("id")
        source.introspectionScope = CatalogScopes.select(DorisCatalogProfile.levels, listOf("warehouse", "analytics"))
        project.service<DorisPipesSettings>().enabled = true
        configure("FROM warehouse.analytics.events |> <caret>")
        val items = myFixture.completeBasic().orEmpty()
        assertNotEmpty(items.toList())
        assertTrue(items.all { LookupElementPresentation.renderElement(it).typeText == "PIPE stage" })
        assertFalse(items.any { it.lookupString == "id" })
        introspections.assertNone()
    }

    fun testPipeColumnCompletionRequestsTheUnloadedParent() {
        val schema = catalog("warehouse").schemas.createOrGet("analytics")
        project.service<DorisPipesSettings>().enabled = true
        configure("FROM warehouse.analytics.events |> SELECT <caret>")
        myFixture.completeBasic()
        introspections.next().finish { schema.tables.createOrGet("events").columns.createOrGet("id") }
        awaitDone(listOf("warehouse", "analytics"))
        PlatformTestUtil.waitWithEventsDispatching("Doris model indexing finishes", {
            !com.intellij.openapi.project.DumbService.getInstance(project).isDumb
        }, 10)
        myFixture.lookup?.hideLookup(false)
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "id")
    }

    fun testPipePathCompletionFollowsCurrentNamespace() {
        catalog("warehouse").schemas.createOrGet("analytics").tables.createOrGet("events").columns.createOrGet("id")
        project.service<DorisPipesSettings>().enabled = true
        configure("FROM analytics.<caret> |> SELECT *")
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            recording.console.dataSource.model = source.model
            recording.console.dataSource.introspectionScope = CatalogScopes.select(DorisCatalogProfile.levels, listOf("warehouse", "analytics"))
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("warehouse", ObjectKind.DATABASE).append("analytics", ObjectKind.SCHEMA)))
            // Its table list was loaded whole this session, as a sync would have.
            DorisListedNamespaces.record(recording.console.dataSource.uniqueId, "warehouse", "analytics")
            DbPsiFacade.getInstance(project).clearCaches()
            myFixture.completeBasic()
            assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "events")
            introspections.assertNone()
        }
    }

    fun testPipeColumnCompletionRefreshesATableWithIndicesButNoColumns() {
        val table = catalog("warehouse").schemas.createOrGet("analytics").tables.createOrGet("events")
        table.indices.createOrGet("event_index")
        assertFalse(DorisCatalogProfile.pathResolver.isChildless(table))
        project.service<DorisPipesSettings>().enabled = true
        configure("FROM warehouse.analytics.events |> SELECT <caret>")
        myFixture.completeBasic()
        introspections.next().finish { table.columns.createOrGet("id") }
        awaitDone(listOf("warehouse", "analytics", "events"))
    }

    fun testPipeJoinColumnLookupUsesCurrentNamespace() {
        val schema = catalog("warehouse").schemas.createOrGet("analytics")
        schema.tables.createOrGet("events").columns.createOrGet("event_id")
        schema.tables.createOrGet("people").columns.createOrGet("person_id")
        project.service<DorisPipesSettings>().enabled = true
        configure("FROM events |> JOIN analytics.people AS p ON true |> SELECT p.<caret>")
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            recording.console.dataSource.model = source.model
            recording.console.dataSource.introspectionScope = CatalogScopes.select(DorisCatalogProfile.levels, listOf("warehouse", "analytics"))
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("warehouse", ObjectKind.DATABASE).append("analytics", ObjectKind.SCHEMA)))
            DbPsiFacade.getInstance(project).clearCaches()
            myFixture.completeBasic()
            assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "person_id")
            introspections.assertNone()
        }
    }

    fun testFlagOffPipeCompletionLabelsSchemasAndCurrentTables() {
        System.setProperty(DorisCatalogs.PROPERTY, "false")
        val model = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
        (model.root as MysqlBaseRoot).schemas.createOrGet("analytics").tables.createOrGet("events").columns.createOrGet("id")
        source.model = model
        source.introspectionScope = CatalogScopes.select(DorisCatalogProfile.levels, listOf("analytics"))
        project.service<DorisPipesSettings>().enabled = true
        configure("FROM <caret> |> SELECT *")
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            recording.console.dataSource.model = model
            recording.console.dataSource.introspectionScope = source.introspectionScope
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("analytics", ObjectKind.SCHEMA)))
            DbPsiFacade.getInstance(project).clearCaches()
            myFixture.completeBasic()
            for ((name, label) in listOf("analytics" to "schema", "events" to "table")) {
                val presentations = myFixture.lookupElements.orEmpty().filter { it.lookupString == name }.map {
                    LookupElementPresentation().apply { it.renderElement(this) }.typeText
                }
                assertContainsElements(presentations, label)
            }
            introspections.assertNone()
        }
    }

    fun testExactCaseAndAbsolutePathsWinBeforeCurrentNamespace() {
        val first = catalog("WAREHOUSE").schemas.createOrGet("analytics")
        val exact = catalog("warehouse").schemas.createOrGet("analytics")
        val roots = source.model.modelRoots.toList()
        assertSame(exact, DorisCatalogProfile.pathResolver.resolve(roots, listOf("warehouse", "analytics"))?.node)
        assertSame(first, DorisCatalogProfile.pathResolver.resolve(roots, listOf("WAREHOUSE", "analytics"))?.node)
        assertSame(exact, DorisCatalogProfile.pathResolver.resolve(roots, listOf("warehouse", "analytics"), listOf("WAREHOUSE"))?.node)
    }

    fun testCommentsAndBothDorisStringQuotesDoNotTrigger() {
        catalog("warehouse")
        for (text in listOf("-- warehouse.<caret>", "SELECT 'warehouse.<caret>'", "SELECT \"warehouse.<caret>\"")) {
            configure(text)
            myFixture.completeBasic()
            introspections.assertNone()
            assertNull(requests.pending(myFixture.file.virtualFile))
        }
    }

    fun testForeignConsoleDoesNotIntrospectTheDorisFileScope() {
        catalog("warehouse")
        configure("SELECT * FROM warehouse.<caret>")
        RecordingConsole(project, myFixture.file.virtualFile, "mysql").use {
            myFixture.completeBasic()
            introspections.assertNone()
        }
    }

    fun testCancellationDuringRequestPropagates() {
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            for (cancellation in listOf(ProcessCanceledException(), CancellationException())) {
                recording.beforeSessionCall = { throw cancellation }
                assertSame(cancellation, runCatching { requests.requestPath(myFixture.file, listOf("warehouse")) }.exceptionOrNull())
            }
        }
    }

    fun testCreateCatalogReloadsTheLoadedNamespaceWithoutChangingScope() {
        catalog("warehouse").schemas.createOrGet("analytics")
        DbPsiFacade.getInstance(project).clearCaches()
        val scope = source.introspectionScope
        RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            val observer = DorisExecutionObserver(project, errors.newScope(testRootDisposable))
            val console = recording.console
            val query = object : DataRequest.QueryRequest(console, "CREATE CATALOG fresh PROPERTIES ('type'='hms')",
                DataRequest.newConstraints(), null) {}
            observer.requestFinished(DataRequest.Context(console.session.messageBus.dataProducer,
                DataRequest.newOutputHandler(), query, DataRequest.AUTO_COMMIT))
            val refresh = introspections.next()
            assertEquals(1, refresh.context.countTasks())
            assertNull("CREATE CATALOG reloads the top-level list", refresh.context.tasks.single().databaseName)
            assertSame(scope, source.introspectionScope)
            assertNull(requests.pending(myFixture.file.virtualFile))
            refresh.finish { catalog("fresh").schemas.createOrGet("new_database") }
            errors.awaitScopes()
            assertSame(scope, source.introspectionScope)
            assertNotNull(DorisCatalogProfile.pathResolver.resolve(source.model.modelRoots.toList(), listOf("fresh", "new_database")))
            assertNull(requests.engine.state(AutoIntrospectEngine.Key(source.uniqueId, emptyList())))
        }
    }

    fun testRefreshCatalogReloadsItsLoadedNamespace() {
        val loaded = catalog("warehouse")
        loaded.schemas.createOrGet("analytics")
        DbPsiFacade.getInstance(project).clearCaches()
        RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            val observer = DorisExecutionObserver(project, errors.newScope(testRootDisposable))
            val console = recording.console
            val query = object : DataRequest.QueryRequest(console, "REFRESH CATALOG warehouse", DataRequest.newConstraints(), null) {}
            observer.requestFinished(DataRequest.Context(console.session.messageBus.dataProducer,
                DataRequest.newOutputHandler(), query, DataRequest.AUTO_COMMIT))
            val refresh = introspections.next()
            assertEquals(1, refresh.context.countTasks())
            assertEquals("warehouse", refresh.context.tasks.single().databaseName)
            assertNull(requests.pending(myFixture.file.virtualFile))
            refresh.finish { loaded.schemas.createOrGet("fresh") }
            errors.awaitScopes()
            assertNotNull(DorisCatalogProfile.pathResolver.resolve(source.model.modelRoots.toList(), listOf("warehouse", "fresh")))
        }
    }

    private fun configure(text: String) {
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("introspect.sql", text)
        SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile,
            TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(source.uniqueId)))))
    }

    private fun catalog(name: String) = ((source.model as BasicModModel).root as MsRoot).databases.createOrGet(name.hashCode().toLong()).apply { this.name = name }

    private fun awaitDone(path: List<String>, sourceId: String = source.uniqueId) {
        PlatformTestUtil.waitWithEventsDispatching("Doris introspection finishes", {
            requests.engine.state(AutoIntrospectEngine.Key(sourceId, path)) == AutoIntrospectEngine.State.Done &&
                requests.pending(myFixture.file.virtualFile) == null
        }, 10)
    }
}
