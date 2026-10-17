package dev.sort.gradle

import java.io.File
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

internal fun newestPluginZip(directory: File, name: String): File? = directory.listFiles()?.asSequence()
    ?.filter { it.isFile && (it.name == "$name.zip" || it.name.startsWith("$name-") && it.name.endsWith(".zip")) }
    ?.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })

/** Tracks which sibling artifact is newest across configuration-cache reuse. */
abstract class LocalPluginZip : ValueSource<String, LocalPluginZip.Parameters> {
    interface Parameters : ValueSourceParameters {
        val checkouts: ListProperty<String>
        val pluginName: Property<String>
    }

    override fun obtain(): String? = parameters.checkouts.get().firstNotNullOfOrNull { checkout ->
        newestPluginZip(File(checkout, "build/distributions"), parameters.pluginName.get())?.absolutePath
    }
}
