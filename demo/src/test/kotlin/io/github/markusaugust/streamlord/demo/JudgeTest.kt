package io.github.markusaugust.streamlord.demo

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The judge, against the service as Main.kt assembles it.
 *
 * What is held here is that the route carries the analysis's verdict unchanged, that a paste the
 * route will not read gets a verdict rather than a status code, and that nothing a reader pastes
 * can make the service answer 500.
 */
class JudgeTest {
    private suspend fun ApplicationTestBuilder.judge(source: String): HttpResponse =
        client.post("/judge") {
            contentType(ContentType.Application.Json)
            setBody(
                buildString {
                    append("{\"source\":\"")
                    for (c in source) {
                        when (c) {
                            '"' -> append("\\\"")
                            '\\' -> append("\\\\")
                            '\n' -> append("\\n")
                            else -> append(c)
                        }
                    }
                    append("\"}")
                },
            )
        }

    @Test
    fun `an interpolated signal in Kotlin is the editors' error, with its fixes as buttons`() =
        testApplication {
            application { live() }

            val body = judge("""dataOnClick("${'$'}count++")""").bodyAsText()

            assertTrue(body.contains("data: selector #verdict"), body)
            assertTrue(body.contains("Kotlin interpolates \$count here"), body)
            assertTrue(body.contains("[kotlin-interpolation]"), body)
            assertTrue(body.contains("Read as Kotlin"), body)

            // The fix is a button that puts the whole fixed line back into the field, not the helper alone.
            assertTrue(body.contains("judge__fix"), body)
            assertTrue(body.contains("""data-on:click="${'$'}source = 'dataOnClick(increment(\x22count\x22))'""""), body)
        }

    @Test
    fun `a mistyped attribute in markup is the editors' warning`() =
        testApplication {
            application { live() }

            val body = judge("""<button data-onn:click="1">Go</button>""").bodyAsText()

            assertTrue(body.contains("Unknown Datastar attribute data-onn"), body)
            assertTrue(body.contains("[unknown-attribute]"), body)
            assertTrue(body.contains("Read as HTML"), body)
        }

    @Test
    fun `clean markup gets the green verdict`() =
        testApplication {
            application { live() }

            val body = judge("""<button data-on:click="@get('/feed')">Hang one</button>""").bodyAsText()

            assertTrue(body.contains("Nothing to fix."), body)
            assertFalse(body.contains("judge__finding"), body)
        }

    @Test
    fun `markup under a line of Kotlin is read as markup, not passed over`() =
        testApplication {
            application { live() }

            val body =
                judge(
                    """
                    dataText("${'$'}{'${'$'}'}a - b")
                    <button data-onn:click="1">
                    <div data-on:click.prevent="${'$'}go()">
                    """.trimIndent(),
                ).bodyAsText()

            assertTrue(body.contains("2 things to fix"), body)
            assertTrue(body.contains("Unknown Datastar attribute data-onn"), body)
            assertTrue(body.contains("data-on:click__prevent"), body)
            assertTrue(body.contains("Read line by line"), body)
        }

    @Test
    fun `a fix to a markup line puts back the whole paste, the Kotlin above it included`() =
        runBlocking {
            val source = "dataText(\"x\")\n<div data-on:click.prevent=\"\$go()\">"
            val finding = judge(source).findings.single()
            val fixed = finding.fixed.first().source

            assertTrue(fixed.startsWith("dataText(\"x\")\n<div data-on:click__prevent"), fixed)
        }

    @Test
    fun `markup inside a raw string stays Kotlin, and a tag over two lines stays one tag`() {
        val raw = "patchElements(\"\"\"\n<li data-text=\"\$x\"></li>\n\"\"\")"
        assertEquals(listOf(false), parts(raw).map { it.html })

        val split = "val x = 1\n<button\n  data-on:click=\"1\">\nval y = 2"
        assertEquals(listOf(false, true, false), parts(split).map { it.html })
        assertEquals("<button\n  data-on:click=\"1\">\n", parts(split)[1].text)
    }

    @Test
    fun `the verdict shows eight findings, eight fixes in all, and counts the rest`() =
        testApplication {
            application { live() }

            // Twelve interpolated signals: twelve findings, four fixes to each.
            val body = judge("dataOnClick(\"" + "\$a".repeat(12) + "\")").bodyAsText()

            assertTrue(body.contains("12 things to fix"), body)
            assertEquals(8, Regex("class=\"judge__finding\"").findAll(body).count(), body)
            assertTrue(body.contains("and 4 more"), body)

            // Two fixes to each of the first four findings, and none to the fifth onwards.
            val findings = body.split("<li class=\"judge__finding\"").drop(1)
            assertEquals(listOf(2, 2, 2, 2, 0, 0, 0, 0), findings.map { Regex("judge__fix").findAll(it).count() }, body)
        }

    @Test
    fun `a paste of quotes with eight findings is eight buttons, not sixteen`() =
        testApplication {
            application { live() }

            // Each button is the paste again, and a quote in it is four bytes.
            val body = judge("dataOnClick(\"" + "\$a".repeat(8) + "'".repeat(990) + "\")").bodyAsText()

            assertEquals(8, Regex("judge__fix").findAll(body).count(), body.take(300))
            assertTrue(body.encodeToByteArray().size < 40_000, body.encodeToByteArray().size.toString())
        }

    /**
     * Over a socket against CIO, because a client refuses to send `%ZZ` and the test engine does
     * not parse requests: the 400 is the engine's BadRequestException reaching the catch-all.
     */
    @Test
    fun `what Ktor itself cannot parse is Ktor's own status, not a 500`() {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1", module = Application::live).start()
        try {
            val port =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            for (target in listOf("/feed?datastar=%7B%22heads%22%3A1%ZZ%7D", "/fe%ZZed")) {
                val response =
                    Socket("127.0.0.1", port).use { socket ->
                        socket.getOutputStream().write(
                            "GET $target HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".encodeToByteArray(),
                        )
                        socket.getInputStream().readBytes().decodeToString()
                    }

                assertTrue(response.startsWith("HTTP/1.1 400 "), response)
                assertTrue(response.endsWith("\r\n\r\nBad Request"), response)
            }
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `one byte over the limit is the verdict about the limit, and no analysis`() =
        testApplication {
            application { live() }

            val source = "<a " + "x".repeat(SOURCE_MAX - 2)
            assertEquals(SOURCE_MAX + 1, source.length)

            val body = judge(source).bodyAsText()

            assertTrue(body.contains("The judge reads at most 1 KiB of UTF-8."), body)
            assertFalse(body.contains("Read as"), body)
        }

    @Test
    fun `the limit counts bytes of UTF-8, not characters`() =
        testApplication {
            application { live() }

            // Half the characters, one byte over: a limit on the length would read this.
            val source = "<a " + "ø".repeat(511)
            assertEquals(SOURCE_MAX + 1, source.encodeToByteArray().size)
            assertTrue(source.length < SOURCE_MAX)

            val body = judge(source).bodyAsText()

            assertTrue(body.contains("The judge reads at most 1 KiB of UTF-8."), body)
            assertFalse(body.contains("Read as"), body)
        }

    @Test
    fun `a paste past the plugin's limit is the same verdict, not a 413`() =
        testApplication {
            application { live() }

            val response = judge("((((".repeat(2000))

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("The judge reads at most 1 KiB of UTF-8."), response.bodyAsText())
        }

    @Test
    fun `signals the route cannot read are a 400 that quotes nothing`() =
        testApplication {
            application { live() }

            val feed = client.get("/feed?datastar=%7B%22heads%22%3A%22abc%22%7D")
            assertEquals(HttpStatusCode.BadRequest, feed.status)
            assertEquals("Signals could not be read", feed.bodyAsText())

            val judge =
                client.post("/judge") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"source":"secretpaste""")
                }
            assertEquals(HttpStatusCode.BadRequest, judge.status)
            assertFalse(judge.bodyAsText().contains("secretpaste"), judge.bodyAsText())
        }

    @Test
    fun `a body that is not JSON by its media type is a 415 that quotes nothing`() =
        testApplication {
            application { live() }

            val response =
                client.post("/judge") {
                    contentType(ContentType.Text.Plain)
                    setBody("""{"source":"secretpaste"}""")
                }

            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
            assertFalse(response.bodyAsText().contains("secretpaste"), response.bodyAsText())
            assertFalse(response.bodyAsText().contains("text/plain"), response.bodyAsText())
        }

    @Test
    fun `a paste of brackets within the limit is read and answered`() =
        testApplication {
            application { live() }

            for (source in listOf("((((".repeat(250), "<" + "((((".repeat(250))) {
                val response = judge(source)

                assertEquals(HttpStatusCode.OK, response.status, source.take(8))
                assertTrue(response.bodyAsText().contains("data: selector #verdict"), response.bodyAsText())
            }
        }

    @Test
    fun `the search and the form still answer under the smaller limit`() =
        testApplication {
            application { live() }

            val search = client.get("/search?datastar=%7B%22query%22%3A%22readSignals%22%7D").bodyAsText()
            assertTrue(search.contains("lt-link"), search)

            val muster =
                client.post("/muster") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"banner":"Ox","swords":"x"}""")
                }
            assertEquals(HttpStatusCode.OK, muster.status)
            assertTrue(muster.bodyAsText().contains("lt-error-summary"), muster.bodyAsText())
        }
}
