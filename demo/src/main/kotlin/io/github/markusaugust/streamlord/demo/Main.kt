package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import io.github.markusaugust.streamlord.html.patchElements
import io.github.markusaugust.streamlord.json.kotlinx.KotlinxSignalsCodec
import io.github.markusaugust.streamlord.ktor.StreamlordPlugin
import io.github.markusaugust.streamlord.ktor.readSignalsOr
import io.github.markusaugust.streamlord.ktor.respondDatastar
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * The signals the search field sends back.
 *
 * `data-bind-query` on the input is the whole of the client side: the browser keeps the value
 * in a signal, ships the store with the request, and this class is the other end of it.
 */
@Serializable
public data class SearchSignals(val query: String = "")

/** Where the documentation is served from. Not configuration: it is our own site. */
private val DOCS_HOSTS = listOf("streamlord-docs.netlify.app", "localhost:4321")

/**
 * streamlord-live: the service the documentation talks to.
 *
 * CIO rather than Netty, because it is pure Kotlin and goes through a GraalVM native image
 * without reflection configuration worth the name. Nothing here is clever; the point is that
 * every search on the site is a real `readSignals` and a real `patch-elements`, so the pages
 * run the library instead of describing it.
 */
public fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, port = port, host = "0.0.0.0", module = Application::live).start(wait = true)
}

public fun Application.live() {
    val index = SearchIndex.load()

    install(StreamlordPlugin) {
        codec = KotlinxSignalsCodec()
    }

    /*
     * The documentation is served from another origin, so the browser asks first.
     *
     * `Datastar-Request` is the one that matters and the one that is easy to miss: the client
     * sets it on every call, it is not on the CORS safelist, and so every request begins with
     * a preflight. Leave it out and the preflight is refused with a 403 that never reaches
     * the page — the search field simply does nothing, with an empty console.
     */
    install(CORS) {
        /*
         * The documentation's own origin is a constant, so it is a default rather than
         * configuration. An environment variable that has to be remembered is a way for the
         * search to disappear from the whole site with an empty console — which is what
         * happened the first time this was deployed, and the failure looks identical to the
         * missing header above. ALLOWED_HOST adds one more for a preview or a fork.
         *
         * localhost stays allowed in production on purpose: developing the pages against the
         * deployed service is useful, and this endpoint reads a search index and holds no
         * credentials, no cookies and nothing to steal.
         */
        for (host in DOCS_HOSTS + listOfNotNull(System.getenv("ALLOWED_HOST"))) {
            allowHost(host, schemes = listOf("http", "https"))
        }

        allowHeader("Datastar-Request")
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowHeader("last-event-id")

        /*
         * Because of that header every search is two round trips, a preflight and the search.
         * Measured: six keystrokes 80 ms apart produce one search, and one preflight with it.
         * A day of cache turns the pair into a single request for the rest of the visit.
         */
        maxAgeInSeconds = 86_400
    }

    install(StatusPages) {
        exception<SignalsTooLargeException> { call, _ ->
            call.respondText("Signals too large", status = HttpStatusCode.PayloadTooLarge)
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        get("/search") {
            val signals = call.readSignalsOr(SearchSignals())
            val hits = index.search(signals.query)

            /*
             * Built before the response opens, so the same event objects can be encoded for
             * the wire panel. What that panel shows is not a description of the frame — it is
             * the frame, from the encoder the golden-file tests verify on this build.
             *
             * (Built out here rather than inside the block for a duller reason too: in there
             * `patchElements` resolves to the stream's own extension, which sends and returns
             * Unit. Out here it is the free function that returns the event.)
             */
            val results =
                patchElements(selector = "#results", mode = ElementPatchMode.INNER) {
                    results(hits, signals.query)
                }
            val totals = PatchSignals("""{"total": ${hits.size}}""")
            val wire =
                patchElements(selector = "#wire", mode = ElementPatchMode.INNER) {
                    frames(listOf(results, totals).map { SseEncoder.encode(it) })
                }

            call.respondDatastar {
                send(results)
                send(totals)
                send(wire)
            }
        }
    }
}
