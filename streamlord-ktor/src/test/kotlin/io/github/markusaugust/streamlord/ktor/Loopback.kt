package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.wire.LOOPBACK
import io.github.markusaugust.streamlord.wire.boundPort
import io.ktor.server.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.catalina.connector.Connector
import kotlin.time.Duration.Companion.seconds
import org.apache.catalina.startup.Tomcat as TomcatServer

/**
 * [engine] serving [module] on [LOOPBACK] and [port], 0 for any free one, started, with the port it
 * listens on. Ktor gives Tomcat's connectors no address and builds them after configureTomcat runs,
 * so on Tomcat the address is set before the start, and each connector is handed to [tomcat] there too.
 */
internal fun <E : ApplicationEngine, C : ApplicationEngine.Configuration> startOnLoopback(
    engine: ApplicationEngineFactory<E, C>,
    module: Application.() -> Unit,
    port: Int = 0,
    configure: C.() -> Unit = {},
    tomcat: (Connector) -> Unit = {},
): Pair<EmbeddedServer<E, C>, Int> {
    val server =
        embeddedServer(
            engine,
            configure = {
                connector {
                    host = LOOPBACK
                    this.port = port
                }
                configure()
            },
            module = module,
        )
    val connectors = tomcatConnectors(server.engine)
    connectors?.forEach {
        it.setProperty("address", LOOPBACK)
        tomcat(it)
    }
    try {
        server.start(wait = false)
        val bound =
            connectors?.let { boundPort(it.first().localPort, "Tomcat") }
                ?: runBlocking {
                    withTimeout(10.seconds) {
                        server.engine
                            .resolvedConnectors()
                            .first()
                            .port
                    }
                }
        return server to bound
    } catch (e: Throwable) {
        server.stop(100, 500)
        throw e
    }
}

/** [test] against [startOnLoopback]'s server, handed its port, which is stopped after. */
internal fun <T, E : ApplicationEngine, C : ApplicationEngine.Configuration> serving(
    engine: ApplicationEngineFactory<E, C>,
    module: Application.() -> Unit,
    port: Int = 0,
    configure: C.() -> Unit = {},
    tomcat: (Connector) -> Unit = {},
    test: (port: Int) -> T,
): T {
    val (server, bound) = startOnLoopback(engine, module, port, configure, tomcat)
    try {
        return test(bound)
    } finally {
        server.stop(100, 500)
    }
}

/** The connectors of Ktor's Tomcat under [engine], or null on another engine. */
private fun tomcatConnectors(engine: ApplicationEngine): Array<Connector>? =
    runCatching {
        engine.javaClass
            .getDeclaredField("server")
            .apply { isAccessible = true }
            .get(engine) as? TomcatServer
    }.getOrNull()
        ?.service
        ?.findConnectors()
