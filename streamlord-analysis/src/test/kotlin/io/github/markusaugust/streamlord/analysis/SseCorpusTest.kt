package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.json.JsonNull
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.fixtures.fixtureNames
import io.github.markusaugust.streamlord.fixtures.fixtureText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The SSE corpus in the VS Code extension's `test/fixtures/sse` is read by this suite, on the JVM and
 * on Node, so both inspectors read a stream alike. Each case runs twice: in its own
 * chunks, and one character at a time, since a chunk may end anywhere.
 */
class SseCorpusTest {
    private val files = fixtureNames("editors/vscode/test/fixtures/sse").filter { it.endsWith(".json") }

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

    @Test
    fun `reads every case of the shared corpus as written`() {
        val cases =
            files.flatMap { file ->
                JsonParser.parseObject(fixtureText("editors/vscode/test/fixtures/sse/$file")).array("cases")!!.map {
                    file to
                        it as JsonObject
                }
            }
        eachCase(cases, { (file, case) -> "$file: ${case.string("name")}" }) { (_, case) ->
            val name = case.string("name")!!
            val chunks = case.array("chunks")!!.strings()
            val expected =
                case.array("messages")!!.map { message(it as JsonObject) } to
                    (case["finish"]?.takeIf { it != JsonNull }?.let { message(it as JsonObject) })
            assertEquals(expected, read(chunks), name)
            assertEquals(expected, read(chunks.joinToString("").map { it.toString() }), "$name, one character at a time")
        }
    }
}
