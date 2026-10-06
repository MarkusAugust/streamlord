package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cases of the VS Code extension's `serverLog.test.ts`. The Spring Boot lines are built from
 * the message in `TomcatWebServer`, `JettyWebServer` and `NettyWebServer` of Boot 3.5 and 4.0,
 * the Ktor line from `EmbeddedServer`'s "Responding at".
 */
class ServerLogTest {
    @Test
    fun `reads where a server says it started`() {
        val cases =
            listOf(
                "2026-10-06 11:39:50,123 [main] INFO  o.s.b.w.e.tomcat.TomcatWebServer - " +
                    "Tomcat started on port 9102 (http) with context path '/'" to "http://localhost:9102",
                "Tomcat started on port 8080 (http) with context path '/app/'" to "http://localhost:8080/app",
                "Tomcat started on ports 8443 (https), 8080 (http) with context path ''" to "https://localhost:8443",
                "Tomcat started on port(s): 8080 (http) with context path ''" to "http://localhost:8080",
                "Jetty started on port 8080 (http/1.1) with context path '/'" to "http://localhost:8080",
                "Netty started on port 8080 (http)" to "http://localhost:8080",
                "INFO  ktor.application - Responding at http://0.0.0.0:8080" to "http://localhost:8080",
                "Responding at http://127.0.0.1:8081" to "http://127.0.0.1:8081",
                "Responding at https://[::]:8443" to "https://localhost:8443",
                "Responding at http://[::1]:8080" to "http://[::1]:8080",
                "\u001B[32m INFO\u001B[0;39m Tomcat started on port \u001B[1m9102\u001B[0m (http) with context path '/'" to
                    "http://localhost:9102",
                "Tomcat initialized with port 8080 (http)" to null,
                "Started Application in 2.1 seconds" to null,
            )
        for ((line, url) in cases) assertEquals(url, ServerLog.startedAt(line), line)
    }

    @Test
    fun `knows a warning or an error from the rest of the log`() {
        val problems =
            listOf(
                "2026-10-06 11:39:56,305 [ndler-158] WARN  o.s.w.s.m.s.DefaultHandlerExceptionResolver  - Resolved [MissingServletRequestParameterException]",
                "ERROR 4242 --- [main] o.s.boot.SpringApplication : Application run failed",
                "Oct 06, 2026 11:39:56 AM org.apache.catalina.core.StandardWrapperValve invoke SEVERE: Servlet.service() threw",
                "2026-10-06T11:39:56.305+02:00  WARN 4242 --- [nio-9102-exec-1] .w.s.m.s.DefaultHandlerExceptionResolver : Resolved",
                // Coloured by Spring Boot for a console that takes it.
                "2026-10-06T11:39:56.305+02:00 \u001B[31mERROR\u001B[0;39m \u001B[35m4242\u001B[0;39m --- [main] Application run failed",
            )
        for (line in problems) assertEquals(true, ServerLog.isProblem(line), line)
        for (line in listOf("INFO  Tomcat started on port 9102 (http)", "DEBUG warnings are off", "Forwarded to /error")) {
            assertEquals(false, ServerLog.isProblem(line), line)
        }
    }
}
