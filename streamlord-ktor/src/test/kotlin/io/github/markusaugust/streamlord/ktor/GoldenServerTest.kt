package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.ExecuteScript
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The official SDK test server, implemented with the Ktor adapter and run in-process against the
 * golden files. This is the `/test` endpoint `datastar-sdk-tests` expects; the same code would
 * pass the external runner on port 7331.
 */
class GoldenServerTest {

    private val golden = File("../streamlord-core/src/test/resources/golden")

    @TestFactory
    fun golden(): List<DynamicTest> = listOf("get", "post").flatMap { kind ->
        File(golden, kind).listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.map { case ->
            DynamicTest.dynamicTest("$kind/${case.name}") {
                testApplication {
                    routing {
                        get("/test") { call.respondDatastar { call.readSignals().events().forEach { send(it) } } }
                        post("/test") { call.respondDatastar { call.readSignals().events().forEach { send(it) } } }
                    }
                    val input = File(case, "input.json").readText()
                    val body = if (kind == "get") {
                        client.get("/test") { parameter("datastar", input) }.bodyAsText()
                    } else {
                        client.post("/test") { contentType(ContentType.Application.Json); setBody(input) }.bodyAsText()
                    }
                    assertEquals(normalize(File(case, "output.txt").readText()), normalize(body))
                }
            }
        }
    }

    private fun JsonObject.events() = array("events")!!.objects().map { e ->
        val id = e.string("eventId")
        val retry = e.int("retryDuration")?.milliseconds
        when (e.string("type")) {
            "patchElements" -> PatchElements(
                elements = e.string("elements"),
                selector = e.string("selector"),
                mode = e.string("mode")?.let(ElementPatchMode::fromWire) ?: ElementPatchMode.DEFAULT,
                useViewTransition = e.boolean("useViewTransition") ?: false,
                eventId = id,
                retry = retry,
            )

            "patchSignals" -> PatchSignals(
                e.string("signals-raw") ?: e.obj("signals")!!.toJson(),
                e.boolean("onlyIfMissing") ?: false,
                id,
                retry,
            )

            else -> ExecuteScript(
                e.string("script")!!,
                e.boolean("autoRemove") ?: true,
                e.obj("attributes")?.mapValues { (_, v) -> v.toKotlin().toString() } ?: emptyMap(),
                id,
                retry,
            )
        }
    }

    /** Order-insensitive within an event, since the golden files themselves disagree on data-line order. */
    private fun normalize(sse: String): List<List<String>> =
        sse.trim().split(Regex("\n\n+")).map { event -> event.lines().map { it.trimEnd() }.sorted() }
}
