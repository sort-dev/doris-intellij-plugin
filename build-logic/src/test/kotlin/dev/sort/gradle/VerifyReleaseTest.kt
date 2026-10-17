package dev.sort.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Test

class VerifyReleaseTest {
    private fun verify(version: String, vararg components: String) =
        ProjectBuilder.builder().build().tasks.register("verifyRelease", VerifyRelease::class.java) {
            pluginVersion.set(version)
            embeddedComponents.set(components.toSet())
        }.get().verify()

    @Test fun stableBaseWithReleasedLibrariesPasses() {
        verify("1.6.1", "dev.brikk.house:brikk-sql-jvm:0.20.0", "dev.brikk.house:brikk-sql-intellij-support:0.2.0")
    }

    @Test fun stableBaseRefusesSnapshotLibraries() {
        assertThrows(IllegalStateException::class.java) {
            verify("0.4.1", "dev.brikk.house:brikk-sql-intellij-support:0.2.0-SNAPSHOT")
        }
    }

    @Test fun devLabelsAreRefused() {
        assertThrows(IllegalStateException::class.java) { verify("0.5.1-dev.202610160000+123456abcdef") }
        assertThrows(IllegalStateException::class.java) { verify("0.1.1-dev.202610160000+123456abcdef.dirty") }
    }
}
