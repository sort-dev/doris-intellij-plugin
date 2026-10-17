package dev.sort.doris.catalog

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.assertFallbackExtensionsAudited
import dev.sort.doris.DorisDbms

class DorisFallbackExtensionAuditTest : BasePlatformTestCase() {
    fun testInheritedExtensionsAreAudited() {
        val allowed = mutableMapOf(
            "com.intellij.database.dataImporter" to "MySQL import defaults use Doris-compatible TEXT, INT and DOUBLE types.",
            "com.intellij.database.dmlHelper" to "Retain existing grid behavior; MySQL's editable-view policy needs a separate Doris review.",
            "com.intellij.database.domainRegistry" to "Retain the MySQL type domains used by the existing model and type-system facade.",
            "com.intellij.database.executionEnvironmentHelper" to "Retain existing MySQL session handling; server-specific probes need a separate Doris review.",
            "com.intellij.database.geoHelper" to "Retain the existing MySQL geometry conversion without adding server queries.",
            "com.intellij.database.gridColumnsManagerFactory" to "The UNKNOWN column manager provides generic result-grid column handling.",
            "com.intellij.database.gridHelper" to "The UNKNOWN grid helper provides generic result-grid editing and presentation.",
            "com.intellij.database.jdbcHelper" to "Retain Connector/J integration; MySQL-specific session operations need a separate Doris review.",
            "com.intellij.database.jdbcMetadataWrapper" to "Retain the MySQL JDBC metadata wrapper for Connector/J-backed metadata reads.",
            "com.intellij.database.jdbcSourceLoader" to "Retain existing SHOW CREATE loading; MySQL routine definitions need a separate Doris review.",
            "com.intellij.database.namingService" to "Retain MySQL identifier quoting for the existing Doris model and script generator.",
            "com.intellij.database.objectEditorFactory" to "Retain existing MySQL schema editors; MySQL-only options need a separate Doris review.",
            "com.intellij.database.objectEditorModelFactory" to "Retain existing MySQL schema-editor models alongside the Doris script generator.",
            "com.intellij.database.routineExecutionHelper" to "MySQL registers the generic routine request builder rather than a MySQL-specific executor.",
            "com.intellij.database.sqlEffectAnalyzer" to "Retain MySQL statement-effect analysis for model refreshes without changing server SQL.",
            "com.intellij.sql.navigationHelper" to "The UNKNOWN navigation helper supplies generic SQL PSI navigation.",
        )
        if (ApplicationInfo.getInstance().build.baselineVersion >= 263) {
            allowed["com.intellij.database.transactionControlLanguageHandler"] =
                "MySQL registers the base transaction-control handler for standard SQL transaction statements."
        }
        assertFallbackExtensionsAudited(DorisDbms.DORIS, allowed)
    }
}
