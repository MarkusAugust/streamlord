package forge.views

import io.github.markusaugust.streamlord.html.*
import kotlinx.html.*

fun FlowContent.broken(count: Int, name: String) {
    div {
        dataText("$count")
        dataOnClick("$count++")
        dataShow("$name.length > 0")
        dataOnClick("@GET('/x')")
        dataOnClick("@get ('/x')")
        dataOnClick("\$count +")
        dataOnClick("")
        dataText("\$my-count")
        dataText("\$neverDefinedInTheForge")
        dataText("/\$count/.test('1')")
        dataOnClick("@clipboard('x')")
        dataOnClick("@post('/x', {contentTyp: 'json'})")
        dataSignals("fooBar", "1")
        dataSignals("count" to 0, "selected" to null)
        dataSignals("is-open" to false)
        dataSignals("foo__bar", "1")
        dataOn("widgetLoaded", "x()") { once = true }
        dataOn("my-event__x", "x()")
        dataClass("isOpen", "\$count > 0")
        dataClass("isOpen_", "true")
        dataStyle("backgroundColor", "'red'")
        dataAttr("viewBox", "'0 0 1 1'")
        dataBind("über")
        dataRef("a:b")
        dataIndicator("constructor")
        dataComputed("total", "\$count; \$count + 1")
        dataOnSignalPatch("evt.detail")
        dataText(signal("is-open"))
        dataOnClick(toggle("is-open"))
        attributes["data-onn:click"] = "x()"
        attributes["data-on:click__debunce.500ms"] = "x()"
        attributes["data-text"] = "\$my-value"
        attributes["data-on:myEvent"] = "\$count++"
        attributes["data-star-text"] = "\$count"
        attributes["data-bind"] = "\$count"
        attributes["data-persist"] = ""
    }
}
