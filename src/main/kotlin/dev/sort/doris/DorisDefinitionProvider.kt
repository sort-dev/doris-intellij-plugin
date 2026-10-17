package dev.sort.doris

import com.intellij.database.model.ObjectKind
import dev.brikk.house.sql.intellij.catalog.ShowCreateDefinitionProvider
import dev.brikk.house.sql.shape.DefinedObject

class DorisDefinitionProvider : ShowCreateDefinitionProvider(
    "doris",
    mapOf(ObjectKind.DATABASE to DefinedObject.CATALOG, ObjectKind.SCHEMA to DefinedObject.SCHEMA,
        ObjectKind.TABLE to DefinedObject.TABLE, ObjectKind.VIEW to DefinedObject.VIEW),
    listOf(ObjectKind.DATABASE, ObjectKind.SCHEMA),
)
