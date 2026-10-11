package dev.sort.doris

import com.intellij.codeInspection.InspectionProfileEntry

internal fun sqlResolveInspection(): InspectionProfileEntry {
    val type = try {
        Class.forName("com.intellij.sql.inspections.SqlResolveInspection")
    } catch (_: ClassNotFoundException) {
        Class.forName("com.intellij.sql.inspections.resolve.SqlResolveInspection")
    }
    return type.getDeclaredConstructor().newInstance() as InspectionProfileEntry
}
