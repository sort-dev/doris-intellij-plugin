package com.brikk.doris

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dialects.AbstractDefinitionProvider
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.DasUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.PairConsumer

class DorisDefinitionProvider : AbstractDefinitionProvider() {
    override fun isSupported(`object`: DasObject): Boolean {
        return `object`.kind in SUPPORTED_KINDS
    }

    override fun fetchSources(
        objects: Iterable<DasObject>,
        connection: DatabaseConnectionCore,
        consumer: PairConsumer<DasObject, Any>
    ) {
        val statement = JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() } ?: return

        try {
            for (obj in objects) {
                try {
                    consumer.consume(obj, loadDefinition(statement, obj) ?: "")
                } catch (t: Throwable) {
                    consumer.consume(obj, t)
                }
            }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }

    private fun loadDefinition(statement: RemoteStatement, obj: DasObject): String? {
        val resultSet = JdbcNativeUtil.computeRemote {
            statement.executeQuery(buildShowCreateSql(obj))
        } ?: return null

        return try {
            extractDefinition(resultSet)
        } finally {
            JdbcNativeUtil.performSafe { resultSet.close() }
        }
    }

    private fun buildShowCreateSql(obj: DasObject): String {
        return when (obj.kind) {
            ObjectKind.TABLE -> buildShowCreateTableSql(obj)
            ObjectKind.VIEW -> buildShowCreateViewSql(obj)
            ObjectKind.SCHEMA, ObjectKind.DATABASE -> buildShowCreateDatabaseSql(obj)
            else -> error("Unsupported Doris object kind: ${obj.kind}")
        }
    }

    private fun buildShowCreateTableSql(obj: DasObject): String {
        return buildQualifiedShowCreate("TABLE", obj)
    }

    private fun buildShowCreateViewSql(obj: DasObject): String {
        return buildQualifiedShowCreate("VIEW", obj)
    }

    private fun buildShowCreateDatabaseSql(obj: DasObject): String {
        return buildString {
            append("SHOW CREATE DATABASE ")
            append(DorisStringUtils.quoteIdentifier(obj.name))
        }
    }

    private fun buildQualifiedShowCreate(objectType: String, obj: DasObject): String {
        val schemaName = DasUtil.getSchema(obj)?.takeUnless { StringUtil.isEmptyOrSpaces(it) }
        return buildString {
            append("SHOW CREATE ")
            append(objectType)
            append(' ')
            if (schemaName != null) {
                append(DorisStringUtils.quoteIdentifier(schemaName))
                append('.')
            }
            append(DorisStringUtils.quoteIdentifier(obj.name))
        }
    }

    private fun extractDefinition(resultSet: RemoteResultSet): String? {
        if (!resultSet.next()) {
            return null
        }

        val metadata = resultSet.metaData
        val columnCount = metadata.columnCount
        var secondColumn: String? = null
        var fallback: String? = null

        for (index in 1..columnCount) {
            val value = resultSet.getString(index)?.trim()
            if (value.isNullOrEmpty()) {
                continue
            }

            if (index == 2 && secondColumn == null) {
                secondColumn = value
            }

            val columnName = metadata.getColumnLabel(index).orEmpty()
            if (columnName.contains("create", ignoreCase = true) || value.startsWith("CREATE ", ignoreCase = true)) {
                return value
            }

            if (fallback == null) {
                fallback = value
            }
        }

        return secondColumn ?: fallback
    }

    private companion object {
        val SUPPORTED_KINDS = setOf(
            ObjectKind.TABLE,
            ObjectKind.VIEW,
            ObjectKind.SCHEMA,
            ObjectKind.DATABASE
        )
    }
}
