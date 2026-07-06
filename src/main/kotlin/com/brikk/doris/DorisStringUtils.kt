package com.brikk.doris

object DorisStringUtils {
    fun escapeIdentifier(value: String): String {
        return value.replace("`", "``")
    }

    fun escapeSqlLiteral(value: String): String {
        return value.replace("'", "''")
    }

    fun quoteIdentifier(value: String): String {
        return buildString(value.length + 2) {
            append('`')
            append(escapeIdentifier(value))
            append('`')
        }
    }
}
