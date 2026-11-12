package forge.names

import kotlinx.html.*

// A data-* name one edit from a Datastar attribute, in an HTML string and in the attributes of the DSL.
fun nearNames() = "<li data-persits data-computd=\"@get('/x')\" data-shwo=\"@post('/x')\" data-no:click=\"x()\" data-indicatorr=\"y\" data-slow data-test=\"login\" data-kind=\"x\" data-theme data-id=\"1\" data-value=\"2\">x</li>"

fun FlowContent.nearNamesCard() {
    div {
        attributes["data-persits"] = ""
        attributes["data-computd"] = "@get('/x')"
        attributes["data-shwo"] = "@post('/x')"
        attributes["data-no:click"] = "x()"
        attributes["data-indicatorr"] = "y"
        attributes["data-slow"] = ""
        attributes["data-test"] = "login"
        attributes["data-kind"] = "x"
        attributes["data-theme"] = ""
        attributes["data-id"] = "1"
        attributes["data-value"] = "2"
    }
}
