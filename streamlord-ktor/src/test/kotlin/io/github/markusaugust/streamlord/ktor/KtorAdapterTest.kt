package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.json.JsonWriter
import io.github.markusaugust.streamlord.core.port.driving.patchSignals
import io.github.markusaugust.streamlord.html.patchElements
import io.github.markusaugust.streamlord.json.kotlinx.KotlinxSignalsCodec
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.flowOf
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KtorAdapterTest {

    @Serializable
    data class Search(val query: String = "", val page: Int = 1)

    @Test
    fun `streams events with the right headers`() = testApplication {
        routing {
            get("/feed") {
                call.respondDatastar {
                    patchElements(selector = "#feed", mode = ElementPatchMode.INNER) { div { id = "x"; +"ash" } }
                    patchSignals("n" to 1)
                    comment("tick")
                }
            }
        }
        val res = client.get("/feed")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("text/event-stream", res.contentType()?.withoutParameters().toString())
        assertEquals("no-cache", res.headers["Cache-Control"])
        assertEquals("no", res.headers["X-Accel-Buffering"])
        assertEquals(
            "event: datastar-patch-elements\ndata: selector #feed\ndata: mode inner\ndata: elements <div id=\"x\">ash</div>\n\n" +
                "event: datastar-patch-signals\ndata: signals {\"n\":1}\n\n: tick\n\n",
            res.bodyAsText(),
        )
    }

    @Test
    fun `streams a flow`() = testApplication {
        routing { get("/flow") { call.respondDatastar(flowOf(PatchSignals("{\"a\":1}"), PatchSignals("{\"b\":2}"))) } }
        assertEquals(
            "event: datastar-patch-signals\ndata: signals {\"a\":1}\n\nevent: datastar-patch-signals\ndata: signals {\"b\":2}\n\n",
            client.get("/flow").bodyAsText(),
        )
    }

    @Test
    fun `reads signals from query and body, typed through the plugin codec`() = testApplication {
        install(StreamlordPlugin) { codec = KotlinxSignalsCodec() }
        routing {
            get("/s") {
                val typed = call.readSignals<Search>()
                val raw = call.readSignals()
                call.respondSignals(JsonWriter.write(mapOf("query" to typed?.query, "page" to raw.int("page"), "ds" to call.isDatastarRequest)))
            }
            post("/s") {
                val typed = call.readSignals<Search>()
                call.respondDatastar { patchSignals(typed ?: Search()) }
            }
            get("/none") { call.respond(if (call.readSignals<Search>() == null && call.readSignals().isEmpty()) "none" else "some") }
        }
        val get = client.get("/s?datastar=%7B%22query%22%3A%22ash%22%2C%22page%22%3A3%7D") { header("Datastar-Request", "true") }
        assertEquals("application/json", get.contentType()?.withoutParameters().toString())
        assertEquals("""{"query":"ash","page":3,"ds":true}""", get.bodyAsText())

        val post = client.post("/s") { contentType(ContentType.Application.Json); setBody("""{"query":"fire","page":2,"_x":1}""") }
        assertEquals("event: datastar-patch-signals\ndata: signals {\"query\":\"fire\",\"page\":2}\n\n", post.bodyAsText())

        assertEquals("none", client.get("/none").bodyAsText())
    }

    @Test
    fun `oversized bodies are cut off at the limit`() = testApplication {
        install(StreamlordPlugin) { maxSignalsSize = 64 }
        routing {
            post("/s") {
                try {
                    call.readSignals()
                    call.respond("ok")
                } catch (e: SignalsTooLargeException) {
                    call.respond(HttpStatusCode.PayloadTooLarge, "too large: " + e.actualSize)
                }
            }
        }
        val big = client.post("/s") { setBody("{\"a\":\"" + "x".repeat(10_000) + "\"}") }
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
        assertEquals("too large: 10008", big.bodyAsText())
        assertEquals("ok", client.post("/s") { setBody("{\"a\":1}") }.bodyAsText())
    }

    @Test
    fun `non-sse responses carry datastar headers`() = testApplication {
        routing {
            get("/el") { call.respondElements("<li>x</li>", selector = "#list", mode = ElementPatchMode.APPEND) }
            get("/js") { call.respondScript("console.log(1)", mapOf("type" to "module")) }
        }
        val el = client.get("/el")
        assertEquals("text/html", el.contentType()?.withoutParameters().toString())
        assertEquals("#list", el.headers["datastar-selector"])
        assertEquals("append", el.headers["datastar-mode"])
        assertNull(el.headers["datastar-use-view-transition"])
        assertEquals("<li>x</li>", el.bodyAsText())

        val js = client.get("/js")
        assertEquals("text/javascript", js.contentType()?.withoutParameters().toString())
        assertEquals("""{"type":"module"}""", js.headers["datastar-script-attributes"])
    }
}
