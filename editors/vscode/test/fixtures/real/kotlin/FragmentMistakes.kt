package forge.fragments

import io.github.markusaugust.streamlord.core.domain.interpolate
import org.intellij.lang.annotations.Language

fun unescaped(title: String) = "<h2 id=\"t\">$title</h2>"

fun inAttribute(title: String) = "<h2 id=\"t\" title=\"$title\">x</h2>"

fun inUrl(target: String) = "<a id=\"go\" href=\"$target\">Go</a>"

fun inScript(code: String) = "<script>$code</script>"

fun inHandler(code: String) = "<button id=\"b\" onclick=\"$code\">x</button>"

fun inExpression(query: String) = """<p id="q" data-text="'$query'"></p>"""

fun unquoted(width: Int) = "<p id=\"w\" width=$width></p>"

fun signalAsTemplate(count: Int) = """<p id="c" data-text="$count + 1"></p>"""

fun chained(name: String) = "<p id=\"n\">" + name + "</p>"

val wrongCase = """<button id="a" data-on:click="@GET('/x')">go</button>"""

val wrongModifier = """<p id="m" data-on:click__debunce.500ms="y()"></p>"""

val wrongAttribute = "<li id=\"l\" data-onn:click=\"x()\">x</li>"

val wrongSyntax = $$"""<div id="s" data-text="$count +"></div>"""

val wrongPrefix = """<p id="p" data-star-text="1"></p>"""

val emptyNonce = """<html data-nonce=""><body></body></html>"""

val hyphen = $$"""<p id="h" data-text="$my-count"></p>"""

val missingSignal = $$"""<p id="u" data-text="$nobodyInTheForgeDefinesThis"></p>"""

@Language("HTML")
val annotatedMistake = "<p data-text:x=\"1\" data-bind></p>"

fun templateInFormat(query: String) = interpolate("<p>$query</p>")

fun tooFewValues() = interpolate("<p>%s and %s</p>", 1)

fun valueInScript(code: String) = interpolate("<script>%s</script>", code)

fun valueInHandler(code: String) = interpolate("<button onclick=\"%s\">x</button>", code)
