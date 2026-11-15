package forge.streams

import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import io.github.markusaugust.streamlord.html.*
import kotlinx.html.*

suspend fun DatastarStream.good(hits: List<String>) {
    patchElements("""<div id="results" data-signals:shown="0"><p data-text="${'$'}shown"></p></div>""")
    patchElements("<li>new</li>", selector = "#results", mode = ElementPatchMode.APPEND)
    patchElements(elements = "<p id=\"status\">done</p>", useViewTransition = true)
    patchElements(selector = "#results", mode = ElementPatchMode.INNER) {
        ul(classes = "results") { hits.forEach { li { +it } } }
    }
    removeElements("#status")
    removeElements(selector = ".stale")
    patchSignals("shown" to hits.size, "lastQuery" to "")
    patchSignals("""{"shown": 3, "nested": {"deep": true}}""")
    removeSignals("lastQuery")
    executeScript("console.log('patched')")
    executeScript(script = "document.title = 'Forge'", autoRemove = false)
}

suspend fun DatastarStream.bad(name: String) {
    patchElements("<b>no id</b>")
    patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
    patchElements("""<div id="a"><span>never closed</div>""")
    patchElements("Hello <b id=\"b\">text at the top</b>")
    patchElements("</div>")
    patchElements("<p id=\"n\">$name</p>")
    patchElements("""<p id="e" data-on:click="@GET('/x')" data-onn:click="1"></p>""")
    removeElements(" ")
    removeElements(selector = "")
    removeElements("#a\n#b")
    patchElements("<i id=\"i\">x</i>", selector = "#a\n")
    executeScript("document.write('</script>')")
    executeScript(script = "alert('$name')")
}
