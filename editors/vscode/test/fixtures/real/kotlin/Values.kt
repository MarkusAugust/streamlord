import io.github.markusaugust.streamlord.html.*
import kotlinx.html.*

// What the readers of a value say of it, in an HTML string and through the DSL.
fun called() = "<button data-on:click=\"@get(function(){ return '/x' }())\">x</button>"

fun grouped() = "<button data-on:click=\"@get((1))\">x</button>"

fun signed() = "<button data-on:click=\"@get(- 1)\">x</button>"

fun computed() = "<div data-computed=\"{t: (1), u: () => 1}\"></div>"

fun unfinished() = "<div data-computed=\"{t: 1 +, u: () => 1}\"></div>"

fun FlowContent.values() {
    button { dataOnClick("@get(function(){ return '/x' }())") }
    button { dataOnClick("@get((1))") }
    button { dataOnClick("@get('/x', {retry: function(){}})") }
    div { attributes["data-computed"] = "(1)" }
    div { attributes["data-computed"] = "{t: (1), u: () => 1}" }
    div { attributes["data-computed"] = "1 +" }
    div {
        dataInit("setTimeout(() => @get('/x'))")
        dataIndicator("later")
    }
    div {
        dataInit("@get('/x')")
        dataIndicator("busy")
    }
}
