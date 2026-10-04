package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.core.protocol.SseEncoder
import io.github.markusaugust.streamlord.html.patchElements
import io.github.markusaugust.streamlord.json.kotlinx.KotlinxSignalsCodec
import io.github.markusaugust.streamlord.ktor.CspNoncePlugin
import io.github.markusaugust.streamlord.ktor.StreamlordPlugin
import io.github.markusaugust.streamlord.ktor.cspNonce
import io.github.markusaugust.streamlord.ktor.readSignalsOr
import io.github.markusaugust.streamlord.ktor.respondDatastar
import io.ktor.http.ContentType
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
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.Serializable
import kotlin.reflect.typeOf

/**
 * The signals the search field sends back.
 *
 * `data-bind:query` on the input is the whole of the client side: the browser keeps the value
 * in a signal, ships the store with the request, and this class is the other end of it.
 *
 * Its serializer is named where the codec is installed below, which is what lets this service be
 * a native image at all. Nothing else about the class is unusual.
 */
@Serializable
public data class SearchSignals(
    val query: String = "",
    /**
     * Whether the caller has somewhere to show the frames.
     *
     * Only the live page has. The search in the masthead is on twenty-two other pages with no
     * `#wire` element, and a patch aimed at a selector that matches nothing is a warning in the
     * reader's console on every keystroke. The page says what it can display; the server does
     * not guess.
     */
    val wire: Boolean = false,
)

/** Where the documentation is served from. Not configuration: it is our own site. */
private val DOCS_HOSTS = listOf("streamlord-docs.netlify.app", "localhost:4321")

/**
 * Which build is answering, baked into the image by the Dockerfile.
 *
 * `/health` returns it rather than "ok", so a caller can tell this deployment from the one it
 * replaced. That distinction is the whole reason it exists: the deploy step asks Railway to
 * redeploy and returns as soon as the request is accepted, while the previous container keeps
 * serving for another half minute. A health check that only looks for 200 passes against it, and
 * CI went green on a deployment that never started. Comparing this against the commit that was
 * pushed is what makes the check about the new image instead of about the endpoint.
 */
private val BUILD: String = System.getenv("BUILD_SHA") ?: "dev"

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

/**
 * Every signals class this service reads, named rather than looked up.
 *
 * One entry per type that reaches `readSignals`, and `requireNamedSerializers` so that one not here
 * fails on the JVM with its own name instead of in the native image on the first request that
 * carries it. That is not hypothetical: three of these four were missing after the demos were
 * written, the JVM resolved them reflectively without a word, and CI caught it only when it
 * started the image and called it.
 *
 * SignalsTest walks this map, so adding a route with a new signals class and forgetting this
 * line fails a test here rather than a container there.
 */
internal val SIGNALS: KotlinxSignalsCodec =
    KotlinxSignalsCodec(
        serializers =
            mapOf(
                typeOf<SearchSignals>() to SearchSignals.serializer(),
                typeOf<CounterSignals>() to CounterSignals.serializer(),
                typeOf<MusterSignals>() to MusterSignals.serializer(),
                typeOf<ModeSignals>() to ModeSignals.serializer(),
            ),
        requireNamedSerializers = true,
    )

public fun Application.live() {
    val index = SearchIndex.load()

    /*
     * The serializer, named rather than looked up.
     *
     * KotlinxSignalsCodec() on its own answers a KType by reading the class, which a native image
     * drops because none of it is in the bytecode; the first search then fails with "Unresolved
     * class: class SearchSignals (kind = CLASS)" on a service that started and answered /health.
     * SearchSignals.serializer() is generated at compile time, so the image can see it. The
     * alternative is a reflect-config.json naming the class, its Companion and its ${'$'}serializer.
     */
    install(StreamlordPlugin) {
        codec = SIGNALS
    }

    /*
     * The documentation is served from another origin, so the browser asks first.
     *
     * `Datastar-Request` is the one that is easy to miss: the client sets it on every call and it
     * is not on the CORS safelist, so every request begins with a preflight. Leave it out and the
     * preflight is refused with a 403 the page never sees, and the search field does nothing.
     */
    install(CORS) {
        /*
         * The documentation's own origin is a constant, so it is a default rather than an
         * environment variable somebody has to remember. ALLOWED_HOST adds one for a preview or
         * a fork.
         *
         * localhost stays allowed in production on purpose: developing the pages against the
         * deployed service is useful, and this endpoint serves a search index with no
         * credentials, no cookies and nothing to steal.
         */
        for (host in DOCS_HOSTS + listOfNotNull(System.getenv("ALLOWED_HOST"))) {
            allowHost(host, schemes = listOf("http", "https"))
        }

        allowHeader("Datastar-Request")
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowHeader("last-event-id")

        // Because of that header every search is two round trips. A day of cache turns the pair
        // into a single request for the rest of the visit.
        maxAgeInSeconds = 86_400
    }

    install(StatusPages) {
        exception<SignalsTooLargeException> { call, _ ->
            call.respondText("Signals too large", status = HttpStatusCode.PayloadTooLarge)
        }
    }

    routing {
        get("/health") { call.respondText(BUILD) }

        /*
         * The one route that serves a document, and so the only one that wants a nonce. The
         * plugin is route-scoped, so the stream endpoints below are not asked to generate one
         * and carry no policy header; a header on a response the browser fetches rather than
         * navigates to governs nothing anyway.
         *
         * The policy is this service's, not the SDK's: jsdelivr serves the client, one style
         * element is on the page, and the stream is same-origin. Csp.kt spells out why each
         * source is there.
         */
        route("/csp") {
            install(CspNoncePlugin) { policy = ::policy }

            get {
                call.respondText(cspPage(call.cspNonce), ContentType.Text.Html)
            }
        }

        // POST, because this is the demo where the request carries the data rather than asks
        // for something, and so exercises the half of readSignals that reads the body.
        post("/muster") {
            call.respondDatastar(validationEvents(call.readSignalsOr(MusterSignals())).asFlow())
        }

        // The eight patch modes, one press at a time. The mode rides in the signals like every
        // other piece of state on these pages.
        get("/modes") {
            call.respondDatastar(modeEvents(call.readSignalsOr(ModeSignals())).asFlow())
        }

        // One connection held open for twelve seconds. Counter.kt builds the Flow and the
        // adapter drains it whole, which is the API this demo exists to show.
        get("/counter") {
            call.respondDatastar(counterEvents(call.readSignalsOr(CounterSignals())))
        }

        get("/search") {
            val signals = call.readSignalsOr(SearchSignals())
            val hits = index.search(signals.query)

            /*
             * Built before the response opens, so the wire panel can encode the same event
             * objects that go out: what it shows is the frame, not a description of it.
             *
             * Out here `patchElements` is the free function that returns an event. Inside the
             * block it would resolve to the stream's own extension, which sends and returns Unit.
             */
            val results =
                patchElements(selector = "#results", mode = ElementPatchMode.INNER) {
                    results(hits, signals.query)
                }
            val totals = PatchSignals("""{"total": ${hits.size}}""")
            val wire =
                if (!signals.wire) {
                    null
                } else {
                    patchElements(selector = "#wire", mode = ElementPatchMode.INNER) {
                        frames(listOf(results, totals).map { SseEncoder.encode(it) })
                    }
                }

            call.respondDatastar {
                send(results)
                send(totals)
                wire?.let { send(it) }
            }
        }
    }
}
