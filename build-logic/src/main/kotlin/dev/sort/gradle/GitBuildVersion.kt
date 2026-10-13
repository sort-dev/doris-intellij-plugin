package dev.sort.gradle

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

internal fun gitBuildIdentity(epochSeconds: Long, sha: String, dirty: Boolean): String =
    DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(epochSeconds)) +
        "+$sha" + if (dirty) ".dirty" else ""

internal fun pluginBuildVersion(base: String, identity: String, release: Boolean): String {
    require(base != "unspecified" && base.isNotBlank()) { "Declare the plugin's base version in build.gradle.kts" }
    return if (release) base else "$base-dev.$identity"
}

/** A configuration-cache input: Git is checked again even when Gradle reuses configuration. */
abstract class GitBuildIdentity : ValueSource<String, GitBuildIdentity.Parameters> {
    interface Parameters : ValueSourceParameters {
        val directory: DirectoryProperty
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        fun git(vararg arguments: String): String {
            val output = ByteArrayOutputStream()
            try {
                execOperations.exec {
                    workingDir(parameters.directory.get().asFile)
                    commandLine(listOf("git") + arguments)
                    environment("GIT_OPTIONAL_LOCKS", "0")
                    standardOutput = output
                }
            } catch (failure: Exception) {
                throw GradleException("Dev plugin builds require a Git checkout with a commit; use -Prelease=true for release/source archives", failure)
            }
            return output.toString(Charsets.UTF_8).trim()
        }
        return gitBuildIdentity(git("show", "-s", "--format=%ct", "HEAD").toLong(),
            git("rev-parse", "--short=12", "HEAD"), git("status", "--porcelain=v1", "--untracked-files=all").isNotEmpty())
    }
}
