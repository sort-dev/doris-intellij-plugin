package dev.sort.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

abstract class SqlDialectPluginExtension {
    abstract val pluginId: Property<String>
    abstract val additionalBundledPlugins: ListProperty<String>
    abstract val excludePlatformKotlin: Property<Boolean>
    abstract val kotlinTests: Property<Boolean>
    abstract val additionalVerifierIdes: ListProperty<String>
    abstract val legalFiles: ListProperty<String>
    abstract val embeddedLibraries: SetProperty<String>
    abstract val bytecodeCeilings: MapProperty<String, Int>
    abstract val requiredNotices: ListProperty<String>
    abstract val internalApiPackages: ListProperty<String>
    abstract val internalApiAllowedIn: ListProperty<String>
}
