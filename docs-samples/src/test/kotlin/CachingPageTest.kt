import io.github.markusaugust.streamlord.html.SignalFilter
import io.github.markusaugust.streamlord.html.dataOnClick
import io.github.markusaugust.streamlord.html.get
import io.github.markusaugust.streamlord.ktor.StreamlordPlugin
import io.github.markusaugust.streamlord.ktor.respondDatastar
import io.github.markusaugust.streamlord.ktor.respondElements
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.html.button
import kotlinx.html.stream.createHTML
import org.apache.catalina.startup.Tomcat
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.reactive.TomcatHttpHandlerAdapter
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The caching page's handlers, run on real servers rather than MockMvc or the test host, since
 * what is promised is what the engine writes. A 304 has to carry the ETag and the Cache-Control
 * of the 200 (RFC 9110, 15.4.5), and the table must not be read again to send it.
 */
class CachingPageTest {
    @Test
    fun `Ktor answers 304 with both headers and reads nothing`() {
        val orders = CountingOrders()
        onKtor({ routing { orderRoutes(orders) } }) { port -> assertCaches(port, orders, ktor = true) }
    }

    // The page says why the header is set in the route: a CachingHeaders rule on the content type
    // misses the 304, which has none.
    @Test
    fun `a CachingHeaders rule on the content type does not reach a 304`() {
        onKtor({
            install(CachingHeaders) {
                options { _, content ->
                    if (content.contentType?.withoutParameters() == ContentType.Text.Html) {
                        CachingOptions(CacheControl.NoCache(CacheControl.Visibility.Private))
                    } else {
                        null
                    }
                }
            }
            routing {
                get("/table") {
                    call.response.header(HttpHeaders.ETag, "\"v1\"")
                    if (call.request.headers[HttpHeaders.IfNoneMatch] == "\"v1\"") {
                        call.respond(HttpStatusCode.NotModified)
                    } else {
                        call.respondElements(orderTable(emptyList(), 1))
                    }
                }
            }
        }) { port ->
            assertEquals(setOf("private", "no-cache"), directives(get(port, "/table")))
            val again = get(port, "/table", "If-None-Match" to "\"v1\"")
            assertEquals(304, again.statusCode())
            assertEquals(emptySet(), directives(again))
        }
    }

    @Test
    fun `a CachingHeaders rule for every response, on the table's route alone, reaches the 304 and not the streams`() {
        onKtor({
            routing {
                route("/table") {
                    install(CachingHeaders) {
                        options { _, _ -> CachingOptions(CacheControl.NoCache(CacheControl.Visibility.Private)) }
                    }
                    get {
                        call.response.header(HttpHeaders.ETag, "\"v1\"")
                        if (call.request.headers[HttpHeaders.IfNoneMatch] == "\"v1\"") {
                            call.respond(HttpStatusCode.NotModified)
                        } else {
                            call.respondElements(orderTable(emptyList(), 1))
                        }
                    }
                }
                get("/stream") { call.respondDatastar { patchElements(orderTable(emptyList(), 1)) } }
            }
        }) { port ->
            assertEquals(setOf("private", "no-cache"), directives(get(port, "/table")))
            assertEquals(setOf("private", "no-cache"), directives(get(port, "/table", "If-None-Match" to "\"v1\"")))
            assertEquals(setOf("no-cache"), directives(get(port, "/stream")))
        }
    }

    // What the page says of a version with a space in it: Ktor refuses the tag, and nothing is a 304.
    @Test
    fun `a version with a space in it is answered in full every time on Ktor`() {
        val spaced =
            object : Orders {
                override fun version(page: Int) = "2026-11-17 10:00"

                override fun page(page: Int) = emptyList<Order>()
            }
        onKtor({ routing { orderRoutes(spaced) } }) { port ->
            val etag = get(port, "/orders").headers().firstValue("ETag").orElseThrow()
            assertEquals(200, get(port, "/orders", "If-None-Match" to etag).statusCode())
        }
    }

    @Test
    fun `a CachingHeaders rule for every response reaches the streams too`() {
        onKtor({
            install(CachingHeaders) { options { _, _ -> CachingOptions(CacheControl.MaxAge(60)) } }
            routing { get("/stream") { call.respondDatastar { patchElements(orderTable(emptyList(), 1)) } } }
        }) { port ->
            assertEquals(setOf("no-cache", "max-age=60"), directives(get(port, "/stream")))
        }
    }

    // The two tabs of the filter on the page: the DSL has to write what the HTML tab shows.
    @Test
    fun `the DSL filter writes the attribute the HTML tab shows`() {
        val button =
            createHTML(prettyPrint = false).button {
                dataOnClick(get("/orders?page=2") { filterSignals = SignalFilter.exclude(".*") })
            }
        assertContains(button, "@get('/orders?page=2', {filterSignals: {exclude: /.*/}})")
    }

    // The number the page gives for what a 304 saves: fifty orders as orderTable writes them.
    @Test
    fun `a page of fifty orders is the size the page says`() {
        val fifty = (1..50).map { Order(1000 + it, "Gorvek", "three swords") }
        onKtor({ routing { get("/fifty") { call.respondElements(orderTable(fifty, 1)) } } }) { port ->
            val response = get(port, "/fifty")
            assertEquals(FIFTY_ORDERS_BYTES, response.headers().firstValueAsLong("Content-Length").orElse(-1))
        }
    }

    @Test
    fun `WebMVC answers 304 with both headers and reads nothing`() {
        mvcOrders = CountingOrders()
        val spring = AnnotationConfigWebApplicationContext().apply { register(Mvc::class.java) }
        onTomcat({ Tomcat.addServlet(it, "dispatcher", DispatcherServlet(spring)) }) { port -> assertCaches(port, mvcOrders) }
    }

    @Test
    fun `WebFlux answers 304 with both headers and reads nothing`() {
        fluxOrders = CountingOrders()
        val handler = WebHttpHandlerBuilder.applicationContext(AnnotationConfigApplicationContext(Flux::class.java)).build()
        onTomcat({ Tomcat.addServlet(it, "reactive", TomcatHttpHandlerAdapter(handler)) }) { port -> assertCaches(port, fluxOrders) }
    }

    private fun assertCaches(
        port: Int,
        orders: CountingOrders,
        ktor: Boolean = false,
    ) {
        val first = get(port, "/orders?page=2")
        assertEquals(200, first.statusCode())
        assertContains(first.body(), """<table id="orders">""")
        val etag = first.headers().firstValue("ETag").orElseThrow()
        assertEquals(setOf("private", "no-cache"), directives(first))

        val again = get(port, "/orders?page=2", "If-None-Match" to etag)
        assertEquals(304, again.statusCode())
        assertEquals("", again.body())
        assertEquals(etag, again.headers().firstValue("ETag").orElse(null))
        assertEquals(setOf("private", "no-cache"), directives(again), "the 304 carries the 200's Cache-Control")
        assertEquals(1, orders.reads, "a 304 reads no orders")
        // RFC 9110, 8.6: a 304 sends no Content-Length, or the 200's. Ktor's engine writes 0 on
        // every answer without a body (BaseApplicationResponse.commitHeaders), and a cache never
        // takes Content-Length from a 304 (RFC 9111, 3.2), so there it is let through.
        val length = again.headers().firstValue("Content-Length").orElse(null)
        val allowed = listOf(null, first.headers().firstValue("Content-Length").orElse(null)) + listOfNotNull("0".takeIf { ktor })
        assertContains(allowed, length, "the 304's Content-Length")

        orders.version++
        val changed = get(port, "/orders?page=2", "If-None-Match" to etag)
        assertEquals(200, changed.statusCode())
        assertNotEquals(etag, changed.headers().firstValue("ETag").orElse(null))
        assertEquals(2, orders.reads)

        assertContains(get(port, "/orders").body(), "Page 1", message = "the page defaults to the first")

        // A tag with parameters is no ETag. Spring reads past the parameter, Ktor's parser throws;
        // either way it must not become a 500.
        for (header in listOf("\"v2-2\";q=0.5", "\"a b\"", "a\"b", "\"a,b\"")) {
            val malformed = get(port, "/orders?page=2", "If-None-Match" to header)
            assertContains(listOf(200, 304), malformed.statusCode(), "If-None-Match: $header is not a failure")
        }
    }

    private fun onKtor(
        module: Application.() -> Unit,
        block: (Int) -> Unit,
    ) {
        val server =
            embeddedServer(CIO, port = 0, host = LOOPBACK) {
                install(StreamlordPlugin)
                module()
            }.start()
        try {
            block(
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                },
            )
        } finally {
            server.stop(100, 500)
        }
    }

    private fun directives(response: HttpResponse<String>): Set<String> =
        response
            .headers()
            .allValues("Cache-Control")
            .flatMap { it.split(",") }
            .map { it.trim() }
            .toSet()

    private fun get(
        port: Int,
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://$LOOPBACK:$port$path"))
        for ((name, value) in headers) request.header(name, value)
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun onTomcat(
        servlet: (org.apache.catalina.Context) -> org.apache.catalina.Wrapper,
        block: (Int) -> Unit,
    ) {
        val baseDir = Files.createTempDirectory("tomcat")
        val tomcat = Tomcat()
        tomcat.setBaseDir(baseDir.toString())
        tomcat.setPort(0)
        tomcat.connector.setProperty("address", LOOPBACK)
        val context = tomcat.addContext("", null)
        servlet(context).apply {
            isAsyncSupported = true
            setLoadOnStartup(1)
            context.addServletMapping("/", name)
        }
        tomcat.start()
        try {
            block(tomcat.connector.localPort)
        } finally {
            tomcat.stop()
            tomcat.destroy()
            // Tomcat records its base directory JVM-wide, for the next instance to make again.
            System.clearProperty("catalina.base")
            System.clearProperty("catalina.home")
            baseDir.toFile().deleteRecursively()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class Mvc {
        @Bean
        fun orderController() = OrderController(mvcOrders)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    class Flux {
        @Bean
        fun orderFluxController() = OrderFluxController(fluxOrders)
    }
}

private const val LOOPBACK = "127.0.0.1"

/** What docs/src/content/docs/caching.md says fifty orders come to. */
private const val FIFTY_ORDERS_BYTES = 2_952L

// Spring builds the controllers from its own configuration classes, so they reach the orders here.
private lateinit var mvcOrders: CountingOrders
private lateinit var fluxOrders: CountingOrders

/** Orders that count how often a page is read, and change when [version] does. */
private class CountingOrders : Orders {
    var version = 1
    var reads = 0

    override fun version(page: Int): String = "v$version-$page"

    override fun page(page: Int): List<Order> {
        reads++
        return listOf(Order(1040 + page, "Gorvek", "three swords"))
    }
}
