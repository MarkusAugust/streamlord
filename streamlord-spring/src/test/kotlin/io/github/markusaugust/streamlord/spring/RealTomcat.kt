package io.github.markusaugust.streamlord.spring

import jakarta.servlet.Filter
import org.apache.catalina.startup.Tomcat
import org.apache.coyote.http2.Http2Protocol
import org.apache.tomcat.util.descriptor.web.FilterDef
import org.apache.tomcat.util.descriptor.web.FilterMap
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * A real Tomcat with a DispatcherServlet over [config], without Boot, on a free port. For what
 * MockMvc never runs: timeouts, threads and executors.
 */
class RealTomcat(
    config: Class<*>,
    http2: Boolean = false,
    filters: List<Filter> = emptyList(),
) : AutoCloseable {
    private val baseDir = Files.createTempDirectory("tomcat")
    private val tomcat = Tomcat()
    val port: Int

    init {
        tomcat.setBaseDir(baseDir.toString())
        tomcat.setPort(0)
        // HTTP/2 over plain HTTP, reached by an upgrade from HTTP/1.1, as Java's client asks for it.
        if (http2) tomcat.connector.addUpgradeProtocol(Http2Protocol())
        val context = tomcat.addContext("", null)
        val spring = AnnotationConfigWebApplicationContext().apply { register(config) }
        Tomcat.addServlet(context, "dispatcher", DispatcherServlet(spring)).apply {
            isAsyncSupported = true
            setLoadOnStartup(1)
        }
        context.addServletMappingDecoded("/", "dispatcher")
        for ((index, filter) in filters.withIndex()) {
            val name = "filter$index"
            context.addFilterDef(
                FilterDef().apply {
                    filterName = name
                    this.filter = filter
                    setAsyncSupported("true")
                },
            )
            context.addFilterMap(
                FilterMap().apply {
                    filterName = name
                    addURLPattern("/*")
                    setDispatcher("REQUEST")
                    setDispatcher("ASYNC")
                },
            )
        }
        tomcat.start()
        port = tomcat.connector.localPort
    }

    /** The response to a GET of [path], its body as lines, read as they arrive. */
    fun get(path: String): HttpResponse<java.util.stream.Stream<String>> =
        HttpClient
            .newHttpClient()
            .send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).build(), HttpResponse.BodyHandlers.ofLines())

    /**
     * The lines of the body of [path] until it ends. A stream that Spring's timeout cuts can end
     * with the connection closed under the reader, which counts as an end here.
     */
    fun lines(path: String): List<String> {
        val lines = mutableListOf<String>()
        try {
            get(path).body().use { body -> body.forEach { lines += it } }
        } catch (_: java.io.UncheckedIOException) {
        }
        return lines
    }

    override fun close() {
        tomcat.stop()
        tomcat.destroy()
        // Tomcat records its base directory as a JVM-wide property, which the next instance would
        // otherwise make again after this one is gone.
        System.clearProperty("catalina.base")
        System.clearProperty("catalina.home")
        baseDir.toFile().deleteRecursively()
    }
}
