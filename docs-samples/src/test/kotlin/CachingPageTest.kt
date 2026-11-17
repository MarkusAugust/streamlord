import io.github.markusaugust.streamlord.ktor.StreamlordPlugin
import io.github.markusaugust.streamlord.ktor.respondDatastar
import io.github.markusaugust.streamlord.ktor.respondElements
import io.ktor.http.CacheControl
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
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
        onKtor({ routing { orderRoutes(orders) } }) { port -> assertCaches(port, orders) }
    }

    @Test
    fun `a CachingHeaders rule on the content type leaves the streams alone`() {
        onKtor({
            cacheTablesOnly()
            tableAndStream()
        }) { port ->
            assertEquals(setOf("private", "no-cache"), directives(get(port, "/table")))
            assertEquals(setOf("no-cache"), directives(get(port, "/stream")))
        }
    }

    // The control: the rule the page warns against does reach a stream, so the test above can tell.
    @Test
    fun `a CachingHeaders rule for every response reaches the streams too`() {
        onKtor({
            install(CachingHeaders) { options { _, _ -> CachingOptions(CacheControl.MaxAge(60)) } }
            tableAndStream()
        }) { port ->
            assertEquals(setOf("no-cache", "max-age=60"), directives(get(port, "/stream")))
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

        orders.version++
        val changed = get(port, "/orders?page=2", "If-None-Match" to etag)
        assertEquals(200, changed.statusCode())
        assertNotEquals(etag, changed.headers().firstValue("ETag").orElse(null))
        assertEquals(2, orders.reads)

        assertContains(get(port, "/orders").body(), "Page 1", message = "the page defaults to the first")
    }

    private fun Application.tableAndStream() {
        routing {
            get("/table") { call.respondElements(orderTable(emptyList(), 1)) }
            get("/stream") { call.respondDatastar { patchElements(orderTable(emptyList(), 1)) } }
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
