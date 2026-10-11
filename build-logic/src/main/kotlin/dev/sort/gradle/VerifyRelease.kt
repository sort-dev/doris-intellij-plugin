package dev.sort.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification only")
abstract class VerifyRelease : DefaultTask() {
    @get:Input
    abstract val pluginVersion: Property<String>

    @get:Input
    abstract val embeddedComponents: SetProperty<String>

    @TaskAction
    fun verify() {
        if (pluginVersion.get().endsWith("-SNAPSHOT")) return
        val snapshots = embeddedComponents.get().filter { it.endsWith("-SNAPSHOT") }
        check(snapshots.isEmpty()) { "Release ${pluginVersion.get()} embeds snapshot libraries: $snapshots" }
    }
}
