package dev.sort.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xml.sax.SAXParseException

class VerifyDistributionTest {
    @get:Rule val temporary = TemporaryFolder(File("build/tmp/verify-distribution").absoluteFile.apply { mkdirs() })

    private val transpiler = "dev.sort.sql-transpiler-intellij-plugin"
    private val siblings = setOf(transpiler) + listOf("clickhouse", "doris", "duckdb", "trino")
        .map { "dev.sort.$it-intellij-plugin" }

    private fun verify(dependencies: String, prolog: String = "") {
        val directory = temporary.newFolder()
        val notices = directory.resolve("THIRD_PARTY_NOTICES.md").apply { writeText("Fixture notices") }
        val pluginJar = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { jar ->
                jar.putNextEntry(ZipEntry("META-INF/plugin.xml"))
                jar.write("""$prolog<idea-plugin><version>1.0</version>$dependencies</idea-plugin>""".toByteArray())
                jar.closeEntry()
                jar.putNextEntry(ZipEntry("META-INF/THIRD_PARTY_NOTICES.md"))
                jar.write(notices.readBytes())
                jar.closeEntry()
            }
        }.toByteArray()
        val archive = directory.resolve("plugin.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("plugin/lib/plugin-1.0.jar"))
            zip.write(pluginJar)
            zip.closeEntry()
        }
        ProjectBuilder.builder().withProjectDir(directory).build()
            .tasks.register("distribution", VerifyDistribution::class.java).get().apply {
                distribution.set(archive)
                this.pluginJar.set("plugin-1.0.jar")
                expectedVersion.set("1.0")
                embeddedLibraries.set(emptySet())
                embeddedComponents.set(emptySet())
                bytecodeCeilings.set(emptyMap())
                legalFiles.from(notices)
                requiredNotices.set(emptyList())
                siblingPluginIds.set(siblings)
                internalApiPackages.set(emptyList())
                internalApiAllowedIn.set(emptyList())
            }.verify()
    }

    @Test fun acceptsOptionalSqlTranspilerIntegrationForEveryDialect() {
        for (dialect in listOf("clickhouse", "doris", "duckdb", "trino")) {
            verify("""<depends optional="true" config-file="$dialect-bsql.xml">$transpiler</depends>""")
        }
    }

    @Test fun acceptsOptionalSiblingIntegrationsWithXmlFormatting() {
        for (sibling in siblings) {
            verify("""
                <depends config-file='integration.xml'
                         optional = 'true'>
                    $sibling
                </depends>
            """.trimIndent())
        }
    }

    @Test fun rejectsRequiredSiblingDependencies() {
        for (sibling in siblings) {
            val failure = assertThrows(IllegalStateException::class.java) {
                verify("<depends>$sibling</depends>")
            }
            assertTrue(failure.message.orEmpty().contains(sibling))
        }
    }

    @Test fun onlyExplicitlyOptionalDependenciesAreAllowed() {
        for (optional in listOf("false", "", "TRUE", "1")) {
            assertThrows(IllegalStateException::class.java) {
                verify("""<depends optional="$optional">$transpiler</depends>""")
            }
        }
    }

    @Test fun optionalDeclarationDoesNotHideARequiredDuplicate() {
        assertThrows(IllegalStateException::class.java) {
            verify("""<depends optional="true">$transpiler</depends><depends>$transpiler</depends>""")
        }
    }

    @Test fun ignoresDependenciesInsideCommentsAndCdata() {
        verify("""
            <!-- <depends>$transpiler</depends> -->
            <description><![CDATA[<depends>$transpiler</depends>]]></description>
        """.trimIndent())
    }

    @Test fun acceptsRequiredPlatformAndKotlinDependencies() {
        verify("""
            <depends>com.intellij.modules.platform</depends>
            <depends>com.intellij.database</depends>
            <depends optional="true" config-file="kotlin.xml">org.jetbrains.kotlin</depends>
        """.trimIndent())
    }

    @Test fun rejectsModernRequiredSiblingDependencies() {
        for (sibling in siblings) {
            val failure = assertThrows(IllegalStateException::class.java) {
                verify("""<dependencies><plugin id="$sibling"/></dependencies>""")
            }
            assertTrue(failure.message.orEmpty().contains(sibling))
        }
    }

    @Test fun rejectsMalformedXml() {
        assertThrows(SAXParseException::class.java) { verify("<depends>") }
    }

    @Test fun rejectsDoctypesAndExternalEntities() {
        assertThrows(SAXParseException::class.java) {
            verify("<depends>&sibling;</depends>",
                """<!DOCTYPE idea-plugin [<!ENTITY sibling SYSTEM "file:///must-not-be-read">]>""")
        }
    }
}
