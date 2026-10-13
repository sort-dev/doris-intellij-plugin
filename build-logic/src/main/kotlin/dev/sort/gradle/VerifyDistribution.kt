package dev.sort.gradle

import java.io.DataInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

private const val JAVA_21 = 65
private const val PLUGIN_XML = "META-INF/plugin.xml"
private const val NOTICES = "META-INF/THIRD_PARTY_NOTICES.md"
private val versionedClass = Regex("^META-INF/versions/\\d+/")

@DisableCachingByDefault(because = "Verification only")
abstract class VerifyDistribution : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val distribution: RegularFileProperty

    @get:Input
    abstract val pluginJar: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val embeddedLibraries: SetProperty<String>

    @get:Input
    abstract val embeddedComponents: SetProperty<String>

    @get:Input
    abstract val bytecodeCeilings: MapProperty<String, Int>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val legalFiles: ConfigurableFileCollection

    @get:Input
    abstract val requiredNotices: ListProperty<String>

    @get:Input
    abstract val siblingPluginIds: SetProperty<String>

    @get:Input
    abstract val internalApiPackages: ListProperty<String>

    @get:Input
    abstract val internalApiAllowedIn: ListProperty<String>

    @TaskAction
    fun verify() {
        ZipFile(distribution.get().asFile).use { zip ->
            val entries = zip.entries().asSequence().filter { it.name.endsWith(".jar") }.toList()
            val duplicates = entries.groupBy { it.name.substringAfterLast('/') }.filterValues { it.size > 1 }.keys
            check(duplicates.isEmpty()) { "Duplicate jars: $duplicates" }
            val jars = entries.associateBy { it.name.substringAfterLast('/') }
            val ownJar = jars[pluginJar.get()] ?: error("Missing ${pluginJar.get()}")
            val expected = embeddedLibraries.get()
            val libraries = jars.keys - pluginJar.get()
            val unexpected = libraries.filter { it !in expected && libraryName(it) !in expected }
            val missing = expected.filter { it !in libraries && libraries.none { jar -> libraryName(jar) == it } }
            val ambiguous = libraries.groupBy(::libraryName).filter { (name, jars) -> name in expected && jars.size > 1 }.keys
            check(unexpected.isEmpty() && missing.isEmpty() && ambiguous.isEmpty()) {
                "Embedded libraries differ from embeddedLibraries: unexpected $unexpected, missing $missing, ambiguous $ambiguous"
            }

            val guarded = internalApiPackages.get()
            val internalUses = mutableListOf<String>()
            for ((name, entry) in jars) {
                val ceiling = bytecodeCeilings.get()[libraryName(name)] ?: JAVA_21
                ZipInputStream(zip.getInputStream(entry)).use { jar ->
                    for (classEntry in generateSequence { jar.nextEntry }.filter { it.name.endsWith(".class") }) {
                        val bytes = jar.readBytes()
                        check(bytes.size >= 8) { "Truncated class $name!/${classEntry.name}" }
                        val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
                        check(major <= ceiling) { "Class file version $major exceeds $ceiling: $name!/${classEntry.name}" }
                        if (guarded.isEmpty()) continue
                        val owner = classEntry.name.replace(versionedClass, "").substringBeforeLast('/', "").replace('/', '.')
                        if (owner.inAny(internalApiAllowedIn.get())) continue
                        referencedClasses(bytes).filter { it.substringBeforeLast('.', "").inAny(guarded) }
                            .forEach { internalUses += "$name!/${classEntry.name} -> $it" }
                    }
                }
            }
            check(internalUses.isEmpty()) { "Internal API used outside ${internalApiAllowedIn.get()}:\n" + internalUses.joinToString("\n") }

            val legal = legalFiles.files.associateBy { "META-INF/${it.name}" }
            legal.values.forEach { check(it.isFile) { "Missing ${it.name}" } }
            val resources = ZipInputStream(zip.getInputStream(ownJar)).use { jar ->
                generateSequence { jar.nextEntry }
                    .filter { it.name in setOf(PLUGIN_XML, NOTICES) || it.name in legal }
                    .associate { it.name to jar.readBytes() }
            }
            for ((path, file) in legal) {
                check(resources[path]?.contentEquals(file.readBytes()) == true) { "$path is missing or differs from ${file.name}" }
            }
            val notices = resources[NOTICES]?.decodeToString() ?: error("THIRD_PARTY_NOTICES.md is not packaged")
            val brikkCoordinates = embeddedComponents.get().filter { it.startsWith("dev.brikk.") }.map { it.substringAfter(':') }
            val absentNotices = (requiredNotices.get() + brikkCoordinates).filter { it !in notices }
            check(absentNotices.isEmpty()) { "THIRD_PARTY_NOTICES.md lacks $absentNotices" }

            val descriptor = resources[PLUGIN_XML]?.decodeToString() ?: error("META-INF/plugin.xml is not packaged")
            val descriptorVersion = Regex("""<version>\s*([^<]+)\s*</version>""").find(descriptor)?.groupValues?.get(1)?.trim()
            check(descriptorVersion == expectedVersion.get()) {
                "plugin.xml version $descriptorVersion differs from archive version ${expectedVersion.get()}"
            }
            for (sibling in siblingPluginIds.get()) {
                check(!Regex("""<depends\b[^>]*>\s*${Regex.escape(sibling)}\s*</depends>""").containsMatchIn(descriptor)) {
                    "plugin.xml depends on $sibling"
                }
            }
        }
        logger.lifecycle("Verified ${distribution.get().asFile.name}")
    }
}

private fun libraryName(jar: String) = jar.removeSuffix(".jar").split('-').takeWhile { it.firstOrNull()?.isDigit() != true }.joinToString("-")

private fun String.inAny(packages: List<String>) = packages.any { this == it || startsWith("$it.") }

private fun referencedClasses(bytes: ByteArray): Set<String> {
    val input = DataInputStream(bytes.inputStream(8, bytes.size - 8))
    val count = input.readUnsignedShort()
    val strings = arrayOfNulls<String>(count)
    val classes = mutableListOf<Int>()
    var index = 1
    while (index < count) {
        when (val tag = input.readUnsignedByte()) {
            1 -> strings[index] = input.readUTF()
            7 -> classes += input.readUnsignedShort()
            8, 16, 19, 20 -> input.skipBytes(2)
            15 -> input.skipBytes(3)
            3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
            5, 6 -> {
                input.skipBytes(8)
                index++
            }
            else -> error("Unknown constant pool tag $tag")
        }
        index++
    }
    val descriptors = strings.asSequence().filterNotNull().flatMap { descriptor.findAll(it).map { match -> match.groupValues[1] } }
    val names = classes.asSequence().mapNotNull { strings[it] }.filterNot { it.startsWith('[') }
    return (names + descriptors).map { it.replace('/', '.') }.toSet()
}

private val descriptor = Regex("L([\\w/$]+)[;<]")
