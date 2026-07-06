package dev.sort.doris

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.validation.DataSourceProblem
import com.intellij.database.dataSource.validation.DatabaseConfigValidator
import com.intellij.database.view.ui.ActualConfigInfoProvider
import com.intellij.openapi.project.Project
import com.intellij.util.Consumer
import java.net.URI

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

        if (!url.startsWith(JDBC_MYSQL_PREFIX, ignoreCase = true)) {
            report(
                problems = problems,
                target = target,
                id = "doris.url.prefix",
                level = DataSourceProblem.Level.WARNING,
                message = "Apache Doris JDBC URL typically starts with jdbc:mysql://.",
                description = "Doris uses the MySQL wire protocol. The standard URL prefix is jdbc:mysql://."
            )
        }

        val uri = runCatching { URI(url.substring(JDBC_PREFIX.length)) }.getOrNull()
        if (uri == null) {
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

        if (uri.host.isNullOrBlank()) {
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

        if (uri.port == MYSQL_DEFAULT_PORT) {
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

        if (uri.port == -1) {
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
    }
}
