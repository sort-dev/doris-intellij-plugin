package dev.sort.doris.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.DocumentationProviderContract

class DorisDocumentationProviderTest : BasePlatformTestCase() {
    fun testEngineDocumentationContract() {
        DocumentationProviderContract(myFixture, DorisSqlDialect.INSTANCE, testRootDisposable)
            .check("CRE<caret>ATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO t SELECT 1", "doris.apache.org", table = "orders")
    }

    fun testPropertyKeyDocumentationContract() {
        DocumentationProviderContract(myFixture, DorisSqlDialect.INSTANCE, testRootDisposable)
            .check("CREATE TABLE t (k INT) PROPERTIES (\"replication_n<caret>um\" = \"1\")", "doris.apache.org", table = "orders")
    }
}
