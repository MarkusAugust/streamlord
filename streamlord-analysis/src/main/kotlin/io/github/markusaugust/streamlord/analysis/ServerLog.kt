package io.github.markusaugust.streamlord.analysis

/** A server the editor saw start, by the line it logged: where it listens, and what the editor calls it. */
public data class RunningServer(
    /** The base URL, as `{{baseUrl}}` would hold it: no trailing slash, the context path included. */
    val url: String,
    /** The run configuration or debug session it was started from. */
    val name: String,
)

/**
 * What a server says about itself in its log: where it listens, and which of its lines belong
 * under a request that failed. Editor-independent; both editors feed it the lines of the
 * processes they start.
 */
public object ServerLog {
    /*
     * Spring Boot writes "<server> started on " + its ports + " with context path '<path>'", the
     * same in 3.5 and 4.0 for Tomcat, Jetty and Undertow, and without a context path for Netty.
     * One port reads "port 9102 (http)", Jetty's "(http/1.1)"; Boot 2 wrote "port(s): 9102 (http)".
     */
    private val SPRING =
        Regex(
            """\b(?:Tomcat|Jetty|Undertow|Netty) started on port(?:s|\(s\))?:? (\d+)(?: \((\w+)[^)]*\))?(?:.*? with context path '([^']*)')?""",
        )

    // Ktor writes "Responding at " + the connector, such as http://0.0.0.0:8080.
    private val KTOR = Regex("""\bResponding at (https?)://(\[[^\]]+]|[^\s/:]+):(\d+)""")

    private val ANY_HOST = setOf("0.0.0.0", "[::]", "::", "*")
    private val PROBLEM = Regex("""\b(?:WARN|WARNING|ERROR|SEVERE|FATAL)\b""")

    // Spring Boot colours the level when the console takes it: ESC[31mERROR has no word boundary.
    private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

    /** [line] without the escape sequences a coloured console log holds. */
    public fun plain(line: String): String = ANSI.replace(line, "")

    /** The base URL a server announces in [line], or null when the line announces nothing. */
    public fun startedAt(line: String): String? {
        val text = plain(line)
        SPRING.find(text)?.let { m ->
            val scheme = if (m.groupValues[2] == "https") "https" else "http"
            return "$scheme://localhost:${m.groupValues[1]}${m.groupValues[3].trimEnd('/')}"
        }
        KTOR.find(text)?.let { m ->
            val host = m.groupValues[2].let { if (it in ANY_HOST) "localhost" else it }
            return "${m.groupValues[1]}://$host:${m.groupValues[3]}"
        }
        return null
    }

    /** Whether [line] belongs under a failed request: a warning or an error, as the common loggers write the level. */
    public fun isProblem(line: String): Boolean = PROBLEM.containsMatchIn(plain(line))
}
