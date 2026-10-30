package interpolationtests

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Every function in way 1 and way 2, rendered, so the output can be compared with the source. */
private fun everything(): String =
    listOf(
        LanguageComment.plain(), LanguageComment.simpleInText(), LanguageComment.propertyInText(),
        LanguageComment.callAndConditional(), LanguageComment.attributeValue(), LanguageComment.partOfClass(),
        LanguageComment.wholeAttribute(), LanguageComment.tagName(), LanguageComment.loop(),
        LanguageComment.datastarSignal(), LanguageComment.rawWithMargin(), LanguageComment.concatenated(),
        LanguageComment.deliberateFindings(), LanguageComment.interpolatedSignalByMistake(),
        LanguageAnnotation.onValue, LanguageAnnotation.onParameter(), LanguageAnnotation.onFunction(),
        LanguageAnnotation.loop(), LanguageAnnotation.datastarSignal(),
    ).joinToString("\n<hr>\n")

fun Application.module() {
    routing {
        get("/") {
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
                  ${everything()}
                  <hr>
                  <ul id="errors" class="lt-list"></ul>
                  <div data-init="@get('/stream')"></div>
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
