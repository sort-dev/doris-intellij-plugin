package dev.sort.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

private const val ORIGIN = "VENDORED-FROM"

private fun File.untracked() = name == ORIGIN || name == "build" || name == "local.properties" || name.startsWith(".") || name.endsWith(".orig")

@DisableCachingByDefault(because = "Verification only")
abstract class VerifyBuildLogic : DefaultTask() {
    @get:Internal
    abstract val directory: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = directory.get().asFile
        val record = root.resolve(ORIGIN)
        check(record.isFile) { "Missing build-logic/$ORIGIN; vendor build-logic with sync-into.sh" }
        val origin = record.readLines().filter { it.isNotBlank() }.associate { it.substringBefore(' ') to it.substringAfter(' ') }
        val tree = treeId(root)?.let { HexFormat.of().formatHex(it) }
        check(tree == origin["tree"]) {
            "build-logic differs from ${origin["repository"]} at ${origin["commit"]}: tree $tree, expected ${origin["tree"]}. " +
                "Change it upstream and run sync-into.sh."
        }
    }
}

private fun treeId(directory: File): ByteArray? {
    val entries = directory.listFiles().orEmpty().filterNot { it.untracked() }.mapNotNull { file ->
        when {
            file.isDirectory -> treeId(file)?.let { Triple("40000", file.name, it) }
            else -> Triple(if (file.canExecute()) "100755" else "100644", file.name, objectId("blob", file.readBytes()))
        }
    }
    if (entries.isEmpty()) return null
    val tree = ByteArrayOutputStream()
    for ((mode, name, id) in entries.sortedBy { (mode, name) -> if (mode == "40000") "$name/" else name }) {
        tree.write("$mode $name\u0000".toByteArray())
        tree.write(id)
    }
    return objectId("tree", tree.toByteArray())
}

private fun objectId(type: String, content: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-1").run {
        update("$type ${content.size}\u0000".toByteArray())
        digest(content)
    }
