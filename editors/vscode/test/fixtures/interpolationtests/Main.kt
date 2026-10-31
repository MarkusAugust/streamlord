package interpolationtests

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Every function in way 1 and way 2, rendered with the query, so the output can be compared with the source. */
private fun everything(query: String): String =
    listOf(
        LanguageComment.plain(query), LanguageComment.simpleInText(query), LanguageComment.propertyInText(query),
        LanguageComment.callAndConditional(query), LanguageComment.attributeValue(query), LanguageComment.partOfClass(query),
        LanguageComment.wholeAttribute(query), LanguageComment.tagName(query), LanguageComment.loop(),
        LanguageComment.datastarSignal(query), LanguageComment.rawWithMargin(query), LanguageComment.concatenated(query),
        LanguageComment.deliberateFindings(query), LanguageComment.interpolatedSignalByMistake(query),
        LanguageAnnotation.onValue, LanguageAnnotation.onParameter(query), LanguageAnnotation.onFunction(query),
        LanguageAnnotation.loop(), LanguageAnnotation.datastarSignal(query),
    ).joinToString("\n<hr>\n")

fun Application.module() {
    routing {
        get("/") {
            val query = call.request.queryParameters["q"].orEmpty()
            // language=HTML
            val html = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>Interpolation tests</title>
                  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@lo-tek/interfaces@0.37.0/dist/lotek.css">
                  <script type="module" src="https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js"></script>
                </head>
                <body data-signals="{open: false}">
                  ${everything(query)}
                  <hr>
                  <ul id="errors" class="lt-list"></ul>
                  <div data-init="@get('/stream?q=$query')"></div>
                </body>
                </html>
            """.trimIndent()
            call.respondText(html, ContentType.Text.Html)
        }
        get("/stream") { call.streamlordPatches() }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8099
    embeddedServer(CIO, port = port, module = Application::module).start(wait = true)
}
