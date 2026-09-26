package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs the official Datastar SDK golden files (sdk/tests/golden) through the encoder.
 *
 * Comparison mirrors the official Go runner: non-data fields must match exactly, data lines are
 * grouped by their first word and compared per group, and `elements` groups are compared as
 * whitespace-normalised HTML.
 */
class GoldenConformanceTest {

    @TestFactory
    fun golden(): List<DynamicTest> = listOf("get", "post").flatMap { kind ->
        val dir = File("src/test/resources/golden/$kind")
        assertTrue(dir.isDirectory, "golden directory missing: ${dir.absolutePath}")
        dir.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.map { case ->
            DynamicTest.dynamicTest("$kind/${case.name}") {
                val input = JsonParser.parseObject(File(case, "input.json").readText())
                val expected = File(case, "output.txt").readText()
                val actual = input.array("events")!!.objects().joinToString("") { SseEncoder.encode(toEvent(it)) }
                assertSseEquivalent(expected, actual)
            }
        }
    }

    /** Maps the golden input format onto Streamlord events; this is what an SDK test server does. */
    private fun toEvent(e: JsonObject): DatastarEvent {
        val eventId = e.string("eventId")
        val retry = e.int("retryDuration")?.milliseconds
        return when (val type = e.string("type")) {
            "patchElements" -> PatchElements(
                elements = e.string("elements"),
                selector = e.string("selector"),
                mode = e.string("mode")?.let(ElementPatchMode::fromWire) ?: ElementPatchMode.DEFAULT,
                useViewTransition = e.boolean("useViewTransition") ?: false,
                eventId = eventId,
                retry = retry,
            )

            "patchSignals" -> PatchSignals(
                signals = e.string("signals-raw") ?: e.obj("signals")!!.toJson(),
                onlyIfMissing = e.boolean("onlyIfMissing") ?: false,
                eventId = eventId,
                retry = retry,
            )

            "executeScript" -> ExecuteScript(
                script = e.string("script")!!,
                autoRemove = e.boolean("autoRemove") ?: true,
                attributes = e.obj("attributes")?.mapValues { (_, v) -> v.toKotlin().toString() } ?: emptyMap(),
                eventId = eventId,
                retry = retry,
            )

            else -> error("unknown golden event type $type")
        }
    }

    private fun assertSseEquivalent(expected: String, actual: String) {
        val exp = parseEvents(expected)
        val act = parseEvents(actual)
        assertEquals(exp.size, act.size, "event count\nexpected:\n$expected\nactual:\n$actual")
        exp.zip(act).forEachIndexed { i, (e, a) ->
            assertEquals(e.fields, a.fields, "event ${i + 1} non-data fields")
            assertEquals(e.data.keys, a.data.keys, "event ${i + 1} data groups")
            for ((group, lines) in e.data) {
                if (group == "elements") {
                    assertEquals(normalizeHtml(lines), normalizeHtml(a.data.getValue(group)), "event ${i + 1} elements")
                } else {
                    assertEquals(lines, a.data.getValue(group), "event ${i + 1} data '$group'")
                }
            }
        }
    }

    private class SseEvent(val fields: Map<String, List<String>>, val data: Map<String, List<String>>)

    private fun parseEvents(text: String): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        var fields = mutableMapOf<String, MutableList<String>>()
        var data = mutableMapOf<String, MutableList<String>>()
        fun flush() {
            if (fields.isNotEmpty() || data.isNotEmpty()) events += SseEvent(fields, data)
            fields = mutableMapOf()
            data = mutableMapOf()
        }
        for (line in text.split("\n")) {
            if (line.isBlank()) {
                flush()
                continue
            }
            val name = line.substringBefore(":")
            val value = line.substringAfter(":").removePrefix(" ")
            if (name == "data") {
                val group = value.substringBefore(" ")
                val content = if (" " in value) value.substringAfter(" ") else ""
                data.getOrPut(group) { mutableListOf() } += content
            } else {
                fields.getOrPut(name) { mutableListOf() } += value
            }
        }
        flush()
        return events
    }

    private fun normalizeHtml(lines: List<String>): String =
        lines.joinToString("\n").replace(Regex("\\s+"), " ").replace("> <", "><").trim()
}
