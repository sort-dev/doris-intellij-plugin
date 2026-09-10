package dev.sort.doris

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.validation.DataSourceProblem
import com.intellij.database.dataSource.validation.DatabaseConfigValidator
import com.intellij.database.view.ui.ActualConfigInfoProvider
import com.intellij.openapi.project.Project
import com.intellij.util.Consumer
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class DorisConfigValidator : DatabaseConfigValidator<LocalDataSource>() {
    override fun getTargetClass(): Class<out LocalDataSource> = LocalDataSource::class.java

    override fun collectProblems(
        project: Project,
        target: LocalDataSource,
        problems: Consumer<in DataSourceProblem>,
        actualConfigInfoProvider: ActualConfigInfoProvider?
    ) {
        if (!isDoris(target)) {
            return
        }

        val url = target.url.orEmpty().trim()
        if (url.isBlank()) {
            report(
                problems = problems,
                target = target,
                id = "doris.url.missing",
                level = DataSourceProblem.Level.ERROR,
                message = "Apache Doris requires a JDBC URL.",
                description = "Use jdbc:mysql://{host}:9030/{database} as the Doris JDBC URL template."
            )
            return
        }

        val connectorUrl = parseConnectorJUrl(url)
        if (connectorUrl == null && !url.startsWith(JDBC_MYSQL_PREFIX, ignoreCase = true)) {
            report(
                problems = problems,
                target = target,
                id = "doris.url.prefix",
                level = DataSourceProblem.Level.WARNING,
                message = "Apache Doris JDBC URL typically starts with jdbc:mysql://.",
                description = "Doris uses the MySQL wire protocol. The standard URL prefix is jdbc:mysql://."
            )
        }

        val parsed = connectorUrl ?: runCatching {
            val uri = URI(url.substring(JDBC_PREFIX.length))
            if (uri.host.isNullOrBlank()) ParsedUrl.MissingHost
            else ParsedUrl.Valid(listOf(uri.port.takeIf { it >= 0 }))
        }.getOrNull()
        if (parsed == null || parsed === ParsedUrl.Invalid) {
            report(
                problems = problems,
                target = target,
                id = "doris.url.invalid",
                level = DataSourceProblem.Level.ERROR,
                message = "JDBC URL is malformed.",
                description = "Expected a URL like jdbc:mysql://localhost:9030/mydb."
            )
            return
        }

        if (parsed === ParsedUrl.MissingHost) {
            report(
                problems = problems,
                target = target,
                id = "doris.host.missing",
                level = DataSourceProblem.Level.ERROR,
                message = "Host is missing from the JDBC URL.",
                description = "Specify a host in the form jdbc:mysql://localhost:9030/mydb."
            )
            return
        }

        parsed as ParsedUrl.Valid
        if (parsed.ports.any { it == MYSQL_DEFAULT_PORT }) {
            report(
                problems = problems,
                target = target,
                id = "doris.port.mysql",
                level = DataSourceProblem.Level.WARNING,
                message = "Port 3306 is the default MySQL port. Apache Doris uses port 9030.",
                description = "The default FE query port for Apache Doris is 9030. " +
                    "Port 3306 is typically used by MySQL. " +
                    "Change the port to 9030 unless you have customized your Doris FE configuration."
            )
        }

        if (parsed.ports.any { it == null }) {
            report(
                problems = problems,
                target = target,
                id = "doris.port.missing",
                level = DataSourceProblem.Level.WARNING,
                message = "JDBC URL does not specify a port.",
                description = "The default Apache Doris FE query port is 9030."
            )
        }
    }

    private sealed interface ParsedUrl {
        data class Valid(val ports: List<Int?>) : ParsedUrl
        object MissingHost : ParsedUrl
        object Invalid : ParsedUrl
    }

    private fun parseConnectorJUrl(url: String): ParsedUrl? {
        val match = MYSQL_SCHEME.find(url) ?: return null
        if (!hasValidPercentEscapes(url)) return ParsedUrl.Invalid
        val tail = url.substring(match.range.last + 1)
        val authorityEnd = tail.indexOfFirst { it == '/' || it == '?' || it == '#' }.let {
            if (it < 0) tail.length else it
        }
        val authority = tail.substring(0, authorityEnd)
        if (authority.isBlank()) return ParsedUrl.MissingHost
        val suffix = tail.substring(authorityEnd)
        if (suffix.startsWith("//")) return ParsedUrl.Invalid
        val queryStart = suffix.indexOf('?')
        val fragmentStart = suffix.indexOf('#')
        val query = if (queryStart >= 0 && (fragmentStart < 0 || queryStart < fragmentStart)) {
            suffix.substring(queryStart + 1, if (fragmentStart < 0) suffix.length else fragmentStart)
        } else {
            ""
        }
        if (query.startsWith('?')) return ParsedUrl.Invalid
        val properties = if (query.isEmpty()) emptyMap() else query.split('&').associate { entry ->
            val key = decode(entry.substringBefore('=', entry)) ?: return ParsedUrl.Invalid
            if (key.isBlank()) return ParsedUrl.Invalid
            key.lowercase() to (decode(entry.substringAfter('=', "")) ?: return ParsedUrl.Invalid)
        }
        val globalPort = properties["port"]?.takeIf { it.isNotEmpty() }?.let(::parsePort)
            ?: if (properties["port"]?.isNotEmpty() == true) return ParsedUrl.Invalid else null

        val hosts = splitTopLevel(authority, ',') ?: return ParsedUrl.Invalid
        if (hosts.isEmpty() || hosts.any { it.isBlank() }) return ParsedUrl.Invalid

        val ports = ArrayList<Int?>()
        for (hostSpec in hosts) {
            when (val endpoint = parseEndpoint(hostSpec.trim())) {
                Endpoint.Invalid -> return ParsedUrl.Invalid
                Endpoint.MissingHost -> return ParsedUrl.MissingHost
                is Endpoint.Valid -> endpoint.ports.forEach { port ->
                    ports.add(port.value ?: globalPort)
                }
            }
        }
        return ParsedUrl.Valid(ports)
    }

    private data class HostPort(val value: Int?, val specified: Boolean)

    private sealed interface Endpoint {
        data class Valid(val ports: List<HostPort>) : Endpoint
        object MissingHost : Endpoint
        object Invalid : Endpoint
    }

    private fun parseEndpoint(spec: String): Endpoint {
        val at = topLevelIndexes(spec, '@') ?: return Endpoint.Invalid
        if (at.size > 1) return Endpoint.Invalid
        val endpoint = if (at.size == 1) spec.substring(at.single() + 1) else spec
        if (endpoint.startsWith("address=", ignoreCase = true)) {
            val body = endpoint.substringAfter('=').trim()
            val matches = HOST_PROPERTY.findAll(body).toList()
            var cursor = 0
            for (match in matches) {
                if (body.substring(cursor, match.range.first).isNotBlank()) return Endpoint.Invalid
                cursor = match.range.last + 1
            }
            if (matches.isEmpty() || body.substring(cursor).isNotBlank()) return Endpoint.Invalid
            return parseHostProperties(matches.asSequence().map { it.groupValues[1] })
        }
        if (endpoint.startsWith('(')) {
            if (!endpoint.endsWith(')')) return Endpoint.Invalid
            return parseHostProperties(endpoint.substring(1, endpoint.length - 1).split(',').asSequence())
        }
        if (endpoint.startsWith('[')) {
            val close = matchingBracket(endpoint)
            if (close <= 1) return Endpoint.Invalid
            val body = endpoint.substring(1, close)
            val hosts = splitTopLevel(body, ',') ?: return Endpoint.Invalid
            val ipv6Literal = hosts.size == 1 && body.count { it == ':' } > 1 &&
                body.none { it == '(' || it == ')' || it == '[' || it == ']' || it == '=' || it == '@' }
            if (close == endpoint.lastIndex && !ipv6Literal) {
                if (hosts.any { it.isBlank() }) return Endpoint.Invalid
                val ports = ArrayList<HostPort>()
                for (host in hosts) {
                    when (val nested = parseEndpoint(host.trim())) {
                        Endpoint.Invalid -> return Endpoint.Invalid
                        Endpoint.MissingHost -> return Endpoint.MissingHost
                        is Endpoint.Valid -> ports.addAll(nested.ports)
                    }
                }
                return Endpoint.Valid(ports)
            }
            val rest = endpoint.substring(close + 1)
            val port = when {
                rest.isEmpty() -> HostPort(null, false)
                rest == ":" -> HostPort(null, true)
                rest.startsWith(':') -> HostPort(
                    decode(rest.substring(1))?.let(::parsePort) ?: return Endpoint.Invalid,
                    true
                )
                else -> return Endpoint.Invalid
            }
            return Endpoint.Valid(listOf(port))
        }
        val colonCount = endpoint.count { it == ':' }
        if (colonCount > 1) return Endpoint.Invalid
        val host = endpoint.substringBefore(':').trim()
        if (host.isEmpty()) return Endpoint.MissingHost
        val portText = endpoint.substringAfter(':', "")
        val port = when {
            colonCount == 0 -> HostPort(null, false)
            portText.isEmpty() -> HostPort(null, true)
            else -> HostPort(decode(portText)?.let(::parsePort) ?: return Endpoint.Invalid, true)
        }
        return Endpoint.Valid(listOf(port))
    }

    private fun parseHostProperties(entries: Sequence<String>): Endpoint {
        val properties = LinkedHashMap<String, String>()
        for (entry in entries) {
            val key = decode(entry.substringBefore('=', "").trim())?.lowercase() ?: return Endpoint.Invalid
            val value = decode(entry.substringAfter('=', "").trim().trim('[', ']')) ?: return Endpoint.Invalid
            if (key.isEmpty()) return Endpoint.Invalid
            properties[key] = value
        }
        if (properties["host"].isNullOrBlank()) return Endpoint.MissingHost
        val port = properties["port"]?.takeIf { it.isNotEmpty() }?.let(::parsePort)
            ?: if (properties["port"]?.isNotEmpty() == true) return Endpoint.Invalid else null
        return Endpoint.Valid(listOf(HostPort(port, "port" in properties)))
    }

    private fun parsePort(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrNull()

    private fun hasValidPercentEscapes(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            if (value[index] != '%') {
                index++
                continue
            }
            if (index + 2 >= value.length || value[index + 1].digitToIntOrNull(16) == null ||
                value[index + 2].digitToIntOrNull(16) == null
            ) return false
            index += 3
        }
        return true
    }

    private fun matchingBracket(value: String): Int {
        var depth = 0
        for ((index, char) in value.withIndex()) {
            when (char) {
                '[' -> depth++
                ']' -> if (--depth == 0) return index
            }
        }
        return -1
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String>? {
        val indexes = topLevelIndexes(text, delimiter) ?: return null
        val result = ArrayList<String>(indexes.size + 1)
        var start = 0
        for (index in indexes) {
            result.add(text.substring(start, index))
            start = index + 1
        }
        result.add(text.substring(start))
        return result
    }

    private fun topLevelIndexes(text: String, target: Char): List<Int>? {
        val indexes = ArrayList<Int>()
        var parens = 0
        var brackets = 0
        for ((index, char) in text.withIndex()) {
            when (char) {
                '(' -> parens++
                ')' -> if (--parens < 0) return null
                '[' -> brackets++
                ']' -> if (--brackets < 0) return null
                target -> if (parens == 0 && brackets == 0) indexes.add(index)
            }
        }
        return indexes.takeIf { parens == 0 && brackets == 0 }
    }

    private fun isDoris(target: LocalDataSource): Boolean {
        val driver = target.databaseDriver
        val driverId = driver?.id.orEmpty()
        val driverName = driver?.name.orEmpty()

        return driverId.startsWith("doris") ||
            driverName.startsWith("Apache Doris", ignoreCase = true) ||
            driverName.startsWith("Doris", ignoreCase = true)
    }

    private fun report(
        problems: Consumer<in DataSourceProblem>,
        target: LocalDataSource,
        id: String,
        level: DataSourceProblem.Level,
        message: String,
        description: String
    ) {
        val text = DataSourceProblem.HyperText(TITLE, message, description)
        problems.consume(DataSourceProblem(target, id, level, text, null))
    }

    private companion object {
        const val TITLE = "Apache Doris configuration"
        const val JDBC_PREFIX = "jdbc:"
        const val JDBC_MYSQL_PREFIX = "jdbc:mysql://"
        const val MYSQL_DEFAULT_PORT = 3306
        val MYSQL_SCHEME = Regex("^jdbc:mysql(?::(?:loadbalance|replication))?://", RegexOption.IGNORE_CASE)
        val HOST_PROPERTY = Regex("\\(([^()]*)\\)")
    }
}
