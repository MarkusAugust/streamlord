package forge.audit

import io.github.markusaugust.streamlord.core.domain.Html
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate
import org.intellij.lang.annotations.Language

// The shapes of the interpolation audit, each as its fix leaves it.
const val SAVE = "/save"

enum class Tab { HOME, AWAY }

class Widget(val text: String)

class User(val name: String, val tags: List<String>)

// No String in a style: the rewrite passes its text to Trusted.
fun styleList(v: List<String>) = "<style>${v}</style>"

fun styleWidget(v: Widget) = "<style>${v}</style>"

// The markup starts a URL that runs: no rewrite, for this value or the other.
fun scriptScheme(v: String) = "<a href=\"javascript:${v}\">x</a>"

fun dataScheme(v: String, w: String) = "<a href=\"data:text/html,${v}\">${w}</a>"

fun schemeInCall(v: String) = interpolate("<a href=\"javascript:%s\">x</a>", v)

// Markup the code holds: nothing in element text, and no rewrite escapes it.
fun trustedHtml(t: Trusted) = "<ul>${t.html}</ul>"

fun htmlRows(rows: List<Html>, a: String) = "<ul>${rows}</ul><p title=\"$a\">x</p>"

// The placeholder fix in interpolate.
fun trimmed(v: String) = interpolate("""
    <p>${v}</p>
""".trimIndent())

fun trustedTitle(v: Trusted) = interpolate("<p title=\"${v}\">x</p>")

fun htmlArea(v: Html) = interpolate("<textarea>${v}</textarea>")

fun maybeFormat(v: String?) = interpolate("<p>${v}</p>")

fun named(v: String) = interpolate(format = "<p>${v}</p>")

// The author's own values stay templates in the format.
fun save(v: String) = "<button data-on:click=\"@post('${SAVE}')\">${v}</button>"

fun tab(v: String) = "<button data-on:click=\"@post('/${Tab.HOME}')\">${v}</button>"

fun dollar(v: String) = "<p data-text=\"${"$"}count\">${v}</p>"

fun route(s: String) = "<p>${s}</p><p>${ROUTE}</p>"

// A String? the rewrite writes as nothing for null.
fun maybe(v: String?) = "<p>${v}</p>"

// HTML by the qualified annotation, and by a parameter of this file's.
@org.intellij.lang.annotations.Language("HTML")
fun qualified(v: String) = "x${v}y"

fun html(@Language("HTML") s: String) = s

fun handed(v: String) = html("x${v}y")

// A literal in a value of a chain.
fun tags(u: User) = "<ul>" + u.tags.map { "<li>$it</li>" }.joinToString("") + "</ul>"

// One title for one edit.
fun link(url: String, name: String) = "<a href=\"$url\">$name</a>"

// What the text shows of numbers and enum names.
fun width(n: Int, v: String) = "<p style=\"width: ${n * 2}px\" title=\"$v\">x</p>"

fun tabName() = "<p data-text=\"${Tab.HOME.name}\">x</p>"

// Digits in code, and true or false in style.
fun scriptNumber(n: Int, v: String) = "<script>var n = ${n};</script><p title=\"$v\">x</p>"

fun styleFlag(b: Boolean) = "<p style=\"order: ${b}\">x</p>"

fun styleBody(v: String) = "<style>p { color: ${v} }</style>"

// Quoting is a step.
fun unquotedValue(v: String) = "<p title=${v}>x</p>"

// A value that opens a chain.
fun leading(v: String, w: String) =
    w + """
    <p>${v}</p>
    """.trimIndent()

// The rewrite of a string inside a template of another.
fun inner(v: String, b: Boolean) = "<p title=\"${if (b) "<li>$v</li>" else ""}\">x</p>"

// Pro's media query.
fun media(v: String) = "<p data-match-media:narrow=\"${v}\"></p>"
