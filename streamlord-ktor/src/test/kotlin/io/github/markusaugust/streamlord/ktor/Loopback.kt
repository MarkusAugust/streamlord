package io.github.markusaugust.streamlord.ktor

import io.ktor.server.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.catalina.connector.Connector
import kotlin.time.Duration.Companion.seconds
import org.apache.catalina.startup.Tomcat as TomcatServer

/** The address the tests serve on and read from. */
internal const val LOOPBACK = "127.0.0.1"

/**
 * [test] against [engine] serving [module] on [port], 0 for any free one, which it is handed. Each
 * Tomcat connector is handed to [tomcat] before the start, as Ktor builds them after configureTomcat runs.
 */
internal fun <T, E : ApplicationEngine, C : ApplicationEngine.Configuration> serving(
    engine: ApplicationEngineFactory<E, C>,
    module: Application.() -> Unit,
    port: Int = 0,
    configure: C.() -> Unit = {},
    tomcat: (Connector) -> Unit = {},
    test: (port: Int) -> T,
): T {
    val server =
        embeddedServer(
            engine,
            configure = {
                connector { this.port = port }
                configure()
            },
            module = module,
        )
    val connectors = tomcatConnectors(server.engine)
    connectors?.forEach(tomcat)
    try {
        server.start(wait = false)
        val bound = connectors?.first()?.localPort ?: runBlocking { withTimeout(10.seconds) { server.engine.resolvedConnectors().first().port } }
        return test(bound)
    } finally {
        server.stop(100, 500)
    }
}

/** The connectors of Ktor's Tomcat under [engine], or null on another engine. */
private fun tomcatConnectors(engine: ApplicationEngine): Array<Connector>? =
    runCatching { engine.javaClass.getDeclaredField("server").apply { isAccessible = true }.get(engine) as? TomcatServer }
        .getOrNull()
        ?.service
        ?.findConnectors()
