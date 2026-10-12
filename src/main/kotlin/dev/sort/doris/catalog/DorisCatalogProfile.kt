package dev.sort.doris.catalog

import com.intellij.database.model.ObjectKind
import dev.brikk.house.sql.intellij.catalog.CatalogProfile
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.sql.dorisDataSourceResolver

object DorisCatalogProfile : CatalogProfile {
    override val dbms = DorisDbms.DORIS
    override val productLabel = "Doris"
    override val quoteChars = "`"
    override val usesCurrentNamespace = true
    override val maxDeepenDepth = 3
    override val autoIntrospect = true
    override val dataSources = dorisDataSourceResolver
    override val levels: List<ObjectKind>
        get() = if (DorisCatalogs.enabled) listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA) else listOf(ObjectKind.SCHEMA)
}
