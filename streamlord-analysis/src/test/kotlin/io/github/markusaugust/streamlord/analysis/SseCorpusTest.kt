package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonNull
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals

/**
 * The SSE corpus in the VS Code extension's `test/fixtures/sse` is read by this suite and by
 * `sseCorpus.test.ts`, so both inspectors read a stream alike. Each case runs twice: in its own
 * chunks, and one character at a time, since a chunk may end anywhere.
 */
class SseCorpusTest {
    private val files = File("../editors/vscode/test/fixtures/sse").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    private fun message(json: JsonObject): SseMessage =
        SseMessage(
            event = json.string("event")!!,
            id = json.string("id"),
            retry = json.long("retry")?.toInt(),
            data = json.array("data")!!.strings(),
            comments = json.array("comments")!!.strings(),
        )

    private fun read(chunks: List<String>): Pair<List<SseMessage>, SseMessage?> {
        val parser = SseParser()
        val messages = chunks.flatMap(parser::feed)
        return messages to parser.finish()
    }

    @TestFactory
    fun `reads every case of the shared corpus as written`(): List<DynamicTest> {
        check(files.isNotEmpty())
        return files.flatMap { file ->
            JsonParser.parseObject(file.readText()).array("cases")!!.map { it as JsonObject }.map { case ->
                val name = case.string("name")!!
                DynamicTest.dynamicTest("${file.name}: $name") {
                    val chunks = case.array("chunks")!!.strings()
                    val expected =
                        case.array("messages")!!.map { message(it as JsonObject) } to
                            (case["finish"]?.takeIf { it != JsonNull }?.let { message(it as JsonObject) })
                    assertEquals(expected, read(chunks), name)
                    assertEquals(expected, read(chunks.joinToString("").map { it.toString() }), "$name, one character at a time")
                }
            }
        }
    }
}
