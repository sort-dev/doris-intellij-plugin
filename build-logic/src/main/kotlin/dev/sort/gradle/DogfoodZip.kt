package dev.sort.gradle

import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Copies a local file")
abstract class DogfoodZip : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val distribution: RegularFileProperty

    @get:Input
    abstract val sinceBuild: Property<String>

    @get:Input
    abstract val untilBuild: Property<String>

    @get:OutputFile
    abstract val archive: RegularFileProperty

    @get:OutputFile
    abstract val checksum: RegularFileProperty

    @TaskAction
    fun copy() {
        val zip = distribution.get().asFile.copyTo(archive.get().asFile, overwrite = true)
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip.readBytes()))
        checksum.get().asFile.writeText("$sha256  ${zip.name}\n")
        logger.lifecycle("$zip\nsha256 $sha256\nIDE builds ${sinceBuild.get()} to ${untilBuild.get()}")
    }
}
