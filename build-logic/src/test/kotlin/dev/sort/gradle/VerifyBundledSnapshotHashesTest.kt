package dev.sort.gradle

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifyBundledSnapshotHashesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val cachedName = "sample-jvmMain-1.0-SNAPSHOT.jar"
    private val localPath = "dev/brikk/house/sample-jvm/1.0-SNAPSHOT/sample-jvm-1.0-SNAPSHOT.jar"

    private fun task(vararg jars: Pair<String, String>): VerifyBundledSnapshotHashes {
        val directory = temporary.newFolder()
        val archive = directory.resolve("plugin.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, content) in jars) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ProjectBuilder.builder().withProjectDir(directory).build()
            .tasks.register("hashes", VerifyBundledSnapshotHashes::class.java).get().apply {
                distribution.set(archive)
                snapshotPaths.set(mapOf(cachedName to localPath))
                mavenLocal.set(directory.resolve("repository"))
            }
    }

    private fun VerifyBundledSnapshotHashes.local(content: String): File =
        mavenLocal.file(localPath).get().asFile.apply { parentFile.mkdirs(); writeText(content) }

    @Test fun acceptsMatchingBytesDespiteMppFilenameRemapping() {
        val task = task("plugin/lib/$cachedName" to "current")
        task.local("current")
        task.verify()
    }

    @Test fun rejectsStaleBundledBytesWithTheFullHashes() {
        val task = task("plugin/lib/$cachedName" to "stale")
        task.local("current")
        val failure = assertThrows(IllegalStateException::class.java) { task.verify() }
        assertTrue(failure.message.orEmpty().contains("differs from Maven Local"))
        assertTrue(Regex("[0-9a-f]{64}").findAll(failure.message.orEmpty()).count() == 2)
    }

    @Test fun rejectsMissingMavenLocalSnapshot() {
        val task = task("plugin/lib/$cachedName" to "current")
        assertTrue(assertThrows(IllegalStateException::class.java) { task.verify() }
            .message.orEmpty().contains("Missing Maven Local snapshot"))
    }

    @Test fun rejectsMissingOrDuplicateBundledSnapshot() {
        for (task in listOf(task(), task("one/lib/$cachedName" to "current", "two/lib/$cachedName" to "current"))) {
            task.local("current")
            assertTrue(assertThrows(IllegalStateException::class.java) { task.verify() }
                .message.orEmpty().contains("Expected exactly one bundled $cachedName"))
        }
    }

    @Test fun needsNoLocalRepositoryWhenThereAreNoSnapshots() {
        val task = task("plugin/lib/released-1.0.jar" to "released")
        task.snapshotPaths.set(emptyMap())
        task.verify()
    }
}
