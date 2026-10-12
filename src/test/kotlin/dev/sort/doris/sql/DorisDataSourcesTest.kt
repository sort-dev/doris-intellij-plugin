package dev.sort.doris.sql

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternUtils
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.DataSourceResolution
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.sort.doris.DorisDbms

class DorisDataSourcesTest : BasePlatformTestCase() {
    private val added = mutableListOf<LocalDataSource>()

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("resolve.sql", "SELECT 1")
    }

    override fun tearDown() {
        try {
            SqlResolveMappings.getInstance(project).apply {
                setMapping(myFixture.file.virtualFile, null)
                setMapping(null, null)
            }
            added.forEach(LocalDataSourceManager.getInstance(project)::removeDataSource)
            DbPsiFacade.getInstance(project).clearCaches()
        } catch (failure: Throwable) {
            addSuppressedException(failure)
        } finally {
            super.tearDown()
        }
    }

    fun testConsoleWinsOverFileScopeAndCompletionCopiesResolve() {
        mapTo(dataSource())
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            val source = recording.console.dataSource
            assertEquals(DataSourceResolution.Console(source), dorisDataSourceResolver.resolve(myFixture.file))
            assertEquals(source.uniqueId, dorisDataSourceResolver.resolve(myFixture.file).dataSource?.uniqueId)
            assertEquals(source.uniqueId, dorisDataSourceResolver.resolve(myFixture.file.copy() as com.intellij.psi.PsiFile).dataSource?.uniqueId)
        }
    }

    fun testForeignConsoleDoesNotUseDorisScopeOrNamespace() {
        mapTo(dataSource())
        RecordingConsole(project, myFixture.file.virtualFile, "mysql").use { recording ->
            assertEquals(Dbms.MYSQL, recording.console.dataSource.dbms)
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("foreign", ObjectKind.SCHEMA)))
            assertEquals(DataSourceResolution.NotFound, dorisDataSourceResolver.resolve(myFixture.file))
            assertNull(dorisDataSourceResolver.resolve(myFixture.file).dataSource)
            assertEquals(emptyList<String>(), dorisDataSourceResolver.consoleNamespace(myFixture.file))
        }
    }

    fun testConsoleNamespaceIsOutermostFirst() {
        assertEquals(emptyList<String>(), dorisDataSourceResolver.consoleNamespace(myFixture.file))
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            assertEquals(emptyList<String>(), dorisDataSourceResolver.consoleNamespace(myFixture.file))
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("internal", ObjectKind.DATABASE).append("analytics", ObjectKind.SCHEMA)))
            assertEquals(listOf("internal", "analytics"), dorisDataSourceResolver.consoleNamespace(myFixture.file))
        }
    }

    fun testFileScopeChoosesDorisSourcesAndRejectsAmbiguity() {
        val first = dataSource()
        val second = dataSource()
        val foreign = dataSource("mysql")
        mapTo(second, foreign)
        assertEquals(DataSourceResolution.FileMapping(second), dorisDataSourceResolver.resolve(myFixture.file))
        assertEquals(second.uniqueId, dorisDataSourceResolver.resolve(myFixture.file).dataSource?.uniqueId)
        mapTo(first, second, foreign)
        val resolution = dorisDataSourceResolver.resolve(myFixture.file) as DataSourceResolution.Ambiguous
        assertEquals(setOf(first, second), resolution.candidates.toSet())
        assertNull(dorisDataSourceResolver.resolve(myFixture.file).dataSource)
    }

    fun testProjectScopeApplies() {
        dataSource()
        val source = dataSource()
        SqlResolveMappings.getInstance(project).setMapping(null, scopeOf(source))
        assertEquals(DataSourceResolution.FileMapping(source), dorisDataSourceResolver.resolve(myFixture.file))
        assertEquals(source.uniqueId, dorisDataSourceResolver.resolve(myFixture.file).dataSource?.uniqueId)
    }

    fun testForeignAndExcludingScopesResolveNothing() {
        val source = dataSource()
        mapTo(dataSource("mysql"))
        assertEquals(DataSourceResolution.NotFound, dorisDataSourceResolver.resolve(myFixture.file))
        SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile,
            TreePatternUtils.minus(SqlResolveMappings.ALL_DATA_SOURCES, scopeOf(source)))
        assertEquals(DataSourceResolution.NotFound, dorisDataSourceResolver.resolve(myFixture.file))
    }

    fun testOnlyDataSourceFallbackIsDisabled() {
        dataSource()
        assertEquals(DataSourceResolution.NotFound, dorisDataSourceResolver.resolve(myFixture.file))
        assertNull(dorisDataSourceResolver.resolve(myFixture.file).dataSource)
        SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile, SqlResolveMappings.ALL_DATA_SOURCES)
        assertEquals(DataSourceResolution.NotFound, dorisDataSourceResolver.resolve(myFixture.file))
    }

    fun testConsoleLookupCancellationPropagates() {
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            val cancellation = ProcessCanceledException()
            recording.beforeSessionCall = { throw cancellation }
            try {
                dorisDataSourceResolver.resolve(myFixture.file)
                fail("Console lookup must propagate cancellation")
            } catch (actual: ProcessCanceledException) {
                assertSame(cancellation, actual)
            }
        }
    }

    private fun dataSource(driver: String = "doris"): LocalDataSource = LocalDataSource().apply {
        name = "Resolver ${added.size}"
        databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver(driver))
        url = "jdbc:offline:resolver"
        isAutoSynchronize = false
    }.also {
        LocalDataSourceManager.getInstance(project).addDataSource(it)
        DbPsiFacade.getInstance(project).clearCaches()
        added += it
        assertEquals(if (driver == "doris") DorisDbms.DORIS else Dbms.MYSQL, it.dbms)
    }

    private fun scopeOf(vararg sources: LocalDataSource) =
        TreePattern(SqlImportUtil.createDataSources(sources.map { ObjectName.quoted(it.uniqueId) }.toTypedArray()))

    private fun mapTo(vararg sources: LocalDataSource) =
        SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile, scopeOf(*sources))
}
