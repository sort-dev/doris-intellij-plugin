package dev.sort.doris.catalog

import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** What an introspection's end tells the IDE: fresh database PSI, and no claim that the data source's settings changed. */
class DorisIntrospectionRefreshTest : BasePlatformTestCase() {
    fun testRefreshClearsPsiCachesWithoutAnnouncingADataSourceChange() {
        val changed = mutableListOf<LocalDataSource?>()
        val listener = object : DataSourceStorage.Listener {
            override fun dataSourceChanged(dataSource: LocalDataSource?, configurationChanged: Boolean) { changed += dataSource }
        }
        project.messageBus.connect(testRootDisposable).subscribe(DataSourceStorage.TOPIC, listener)
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(DataSourceStorage.TOPIC, listener)
        val facade = DbPsiFacade.getInstance(project)
        val before = facade.modificationCount
        refreshEditorsAfterIntrospection(project)
        assertTrue(facade.modificationCount > before)
        assertEmpty(changed)
    }
}
