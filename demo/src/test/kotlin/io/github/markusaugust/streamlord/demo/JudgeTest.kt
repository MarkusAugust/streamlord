package io.github.markusaugust.streamlord.demo

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
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

            assertTrue(body.contains("The browser will see exactly this."), body)
            assertFalse(body.contains("judge__finding"), body)
        }

    @Test
    fun `one character over the limit is the verdict about the limit, and no analysis`() =
        testApplication {
            application { live() }

            val source = "<a " + "x".repeat(SOURCE_MAX - 2)
            assertEquals(SOURCE_MAX + 1, source.length)

            val body = judge(source).bodyAsText()

            assertTrue(body.contains("The judge reads at most 1 KiB."), body)
            assertFalse(body.contains("Read as"), body)
        }

    @Test
    fun `a paste past the plugin's limit is the same verdict, not a 413`() =
        testApplication {
            application { live() }

            val response = judge("((((".repeat(2000))

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("The judge reads at most 1 KiB."), response.bodyAsText())
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
            assertTrue(search.contains("fs-link"), search)

            val muster =
                client.post("/muster") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"banner":"Ox","swords":"x"}""")
                }
            assertEquals(HttpStatusCode.OK, muster.status)
            assertTrue(muster.bodyAsText().contains("fs-error-summary"), muster.bodyAsText())
        }
}
