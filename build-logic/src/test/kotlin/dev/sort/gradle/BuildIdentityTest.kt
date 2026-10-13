package dev.sort.gradle

import java.nio.file.Files
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun versionsUseCommitTimeInUtcAndKeepTheReleaseBase() {
        val clean = gitBuildIdentity(0, "123456abcdef", false)
        assertEquals("197001010000+123456abcdef", clean)
        assertEquals("0.4.0-dev.$clean", pluginBuildVersion("0.4.0", clean, false))
        assertEquals("0.4.0", pluginBuildVersion("0.4.0", clean, true))
        assertEquals("197001010000+123456abcdef.dirty", gitBuildIdentity(0, "123456abcdef", true))
    }

    @Test fun gitIdentityIncludesTrackedAndUntrackedChangesButNotIgnoredBuilds() {
        val directory = temporary.newFolder()
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).directory(directory).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(output, 0, process.waitFor())
            return output
        }
        git("init", "-q", "--initial-branch=main")
        git("config", "user.name", "Build fixture")
        git("config", "user.email", "build-fixture@example.invalid")
        directory.resolve(".gitignore").writeText("build/\n.gradle/\nuserHome/\n")
        directory.resolve("source.txt").writeText("clean")
        git("add", ".")
        git("commit", "-qm", "Fixture")
        fun identity(): String {
            val project = ProjectBuilder.builder().withProjectDir(directory).build()
            return project.providers.of(GitBuildIdentity::class.java) { parameters.directory.set(directory) }.get()
        }
        val clean = identity()
        assertFalse("$clean ${git("status", "--porcelain=v1", "--untracked-files=all")}", clean.endsWith(".dirty"))
        assertEquals(clean, identity())
        Files.createDirectories(directory.resolve("build").toPath())
        directory.resolve("build/output.zip").writeText("ignored")
        assertEquals(clean, identity())
        directory.resolve("source.txt").writeText("modified")
        assertEquals("$clean.dirty", identity())
        directory.resolve("source.txt").writeText("clean")
        directory.resolve("new.txt").writeText("untracked")
        assertEquals("$clean.dirty", identity())
    }

    @Test fun siblingDiscoveryChoosesNewestMatchingVersionAndAcceptsLegacy() {
        val directory = temporary.newFolder()
        val legacy = directory.resolve("doris-intellij-plugin.zip").apply { writeText("old"); setLastModified(1000) }
        assertEquals(legacy, newestPluginZip(directory, "doris-intellij-plugin"))
        val newer = directory.resolve("doris-intellij-plugin-1.6.0-dev.202610130100+123456abcdef.zip").apply { writeText("new"); setLastModified(2000) }
        directory.resolve("another-plugin.zip").apply { writeText("not a peer"); setLastModified(3000) }
        assertEquals(newer, newestPluginZip(directory, "doris-intellij-plugin"))
        assertNull(newestPluginZip(directory, "missing-plugin"))
    }
}
