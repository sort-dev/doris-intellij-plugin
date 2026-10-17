package dev.sort.gradle

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Explicit verification against mutable Maven Local snapshots")
abstract class VerifyBundledSnapshotHashes : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val distribution: RegularFileProperty

    /** ZIP filename to Maven repository path; only the snapshots being validated. */
    @get:Input
    abstract val snapshotPaths: MapProperty<String, String>

    @get:Internal
    abstract val mavenLocal: DirectoryProperty

    @TaskAction
    fun verify() {
        val snapshots = snapshotPaths.get()
        if (snapshots.isEmpty()) {
            logger.lifecycle("No bundled dev.brikk.house snapshots to validate")
            return
        }
        ZipFile(distribution.get().asFile).use { zip ->
            val jars = zip.entries().asSequence().filter { it.name.endsWith(".jar") }
                .groupBy { it.name.substringAfterLast('/') }
            for ((name, path) in snapshots.toSortedMap()) {
                val entry = jars[name]?.singleOrNull() ?: error("Expected exactly one bundled $name")
                val local = mavenLocal.file(path).get().asFile
                check(local.isFile) { "Missing Maven Local snapshot: $local" }
                val expected = local.inputStream().use(::sha256)
                val actual = zip.getInputStream(entry).use(::sha256)
                check(actual == expected) { "Bundled $name SHA-256 $actual differs from Maven Local $local SHA-256 $expected" }
                logger.lifecycle("$actual  $name")
            }
        }
        logger.lifecycle("Bundled snapshot SHA-256 matches Maven Local: ${distribution.get().asFile}")
    }
}

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
