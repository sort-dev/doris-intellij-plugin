package dev.sort.doris.pipes

import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.SqlScriptParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DorisCoreScannerParityTest {
    @Test
    fun `core scanners match native corpus except accepted known differences`() {
        val corpus = Json.parseToJsonElement(Files.readString(
            Path.of(System.getProperty("corpus.dir")).resolveSibling("parity").resolve("doris-scanners.json"),
        )).jsonObject
        val knownDifferences = corpus.getValue("knownDifferences").jsonObject.mapValues { (_, axes) ->
            axes.jsonArray.map { it.jsonPrimitive.content }.toSet()
        }
        val cases = corpus.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(cases.size, cases.map { it.getValue("id") }.toSet().size)
        assertTrue(knownDifferences.keys.all { id ->
            cases.any { it.getValue("id").jsonPrimitive.content == id }
        })
        val differences = ArrayList<JsonElement>()
        val unexpected = ArrayList<String>()
        val resolved = ArrayList<String>()
        for (case in cases) {
            val id = case.getValue("id").jsonPrimitive.content
            val sql = case.getValue("input").jsonPrimitive.content.let { input ->
                case["projectionCount"]?.jsonPrimitive?.int?.let { count ->
                    input.replace("{projections}", (1..count).joinToString(", ") { "$it AS c$it" })
                } ?: input
            }
            val expected = knownDifferences[id].orEmpty()
            assertTrue(id, expected.all { it in setOf("statements", "parameters") })
            val native = mapOf(
                "statements" to JsonArray(DorisNativeScanner.chunks(sql).map {
                    statement(it.startOffset, it.endOffset, it.hasSql, it.hasPipeOperator, it.boundaryError != null)
                }),
                "parameters" to JsonArray(DorisNativeScanner.namedParameterRanges(sql).map {
                    parameter(sql.substring(it.first + 1, it.last + 1), it.first, it.last + 1)
                }),
            )
            val core = mapOf(
                "statements" to JsonArray(SqlScriptSnapshot.parse(sql, "doris").statements.map {
                    statement(it.start, it.end, it.hasSql, it.hasPipe, it.boundaryError != null)
                }),
                "parameters" to coreParameters(sql),
            )
            for (axis in native.keys) {
                if (native[axis] == core[axis]) {
                    if (axis in expected) resolved += "$id/$axis"
                    continue
                }
                val category = case.getValue("group").jsonPrimitive.content
                val group = when {
                    axis == "parameters" && id.contains("unterminated") -> "opaque-tail-parameters"
                    axis == "parameters" && core[axis] is JsonObject &&
                        (core[axis] as JsonObject)["errorType"] == JsonPrimitive("ShapeError") -> "parameter-refusal"
                    axis == "parameters" && core[axis] is JsonObject &&
                        category !in setOf("comments", "dollar", "hints") -> "parameter-refusal"
                    else -> category
                }
                differences += buildJsonObject {
                    put("id", id)
                    put("group", group)
                    put("axis", axis)
                    put("input", sql)
                    put("native", native.getValue(axis))
                    put("core", core.getValue(axis))
                    put("expectedFailure", axis in expected)
                    put("judgement", corpus.getValue("evidence").jsonObject.getValue(group))
                }
                if (axis !in expected) unexpected += "$id/$axis"
            }
        }
        val report = buildJsonObject {
            put("corpusSize", cases.size)
            put("disagreementCount", differences.size)
            put("disagreeingCases", differences.map { it.jsonObject.getValue("id") }.toSet().size)
            put("resolvedExpectedFailures", JsonArray(resolved.map(::JsonPrimitive)))
            put("groups", JsonObject(differences.groupBy { it.jsonObject.getValue("group").jsonPrimitive.content }
                .mapValues { JsonArray(it.value) }))
        }
        val output = Path.of("build", "scanner-parity", "disagreements.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), report))
        println("Doris scanner parity: ${cases.size} cases, ${differences.size} disagreements, ${resolved.size} resolved expected failures; $output")
        assertTrue("Unexpected scanner disagreements: ${unexpected.joinToString()}; see $output", unexpected.isEmpty())
        assertTrue("Resolved known differences: ${resolved.joinToString()}", resolved.isEmpty())
    }

    private fun statement(start: Int, end: Int, hasSql: Boolean, hasPipe: Boolean, boundaryError: Boolean) = buildJsonObject {
        put("start", start)
        put("end", end)
        put("hasSql", hasSql)
        put("hasPipeOperator", hasPipe)
        put("boundaryError", boundaryError)
    }

    private fun parameter(name: String, start: Int, end: Int) = buildJsonObject {
        put("name", name)
        put("start", start)
        put("end", end)
    }

    private fun coreParameters(sql: String): JsonElement =
        JsonArray(SqlScriptParameters.parse(sql, "doris").occurrences.filter { it.style == ParamStyle.NAMED_COLON }.map {
            parameter(it.name!!, it.range.start, it.range.end)
        })

}
