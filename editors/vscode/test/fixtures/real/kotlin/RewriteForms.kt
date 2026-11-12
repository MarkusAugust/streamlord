package forge.rewrite

import io.github.markusaugust.streamlord.core.domain.Html
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

const val REWRITE_KIND = "card"

// How the literal is written: a multi-dollar literal keeps its prefix, and a single dollar stays text.
fun multiDollar(a: String, b: String) = $$"""<p title="$${a}" lang="en">$5 for $${b}</p>"""

fun threeDollars(a: String) = $$$"""<p title="$$${a}">$5 and $$5</p>"""

// Literals joined with + become one format.
fun joined(a: String, b: String) = "<p title=\"" + a + "\">" + b + "</p>"

fun joinedPercent(q: String) = "<p>50% " + q + "</p>"

// A trim call stays on the format.
fun margin(a: String, b: String) = """<p title="$a"
    |   lang="${b}">x</p>""".trimMargin()

fun indented(a: String, b: String) = """
    <p title="$a" lang="${b}">x</p>
    """.trimIndent()

// What the value is.
fun nullable(q: String?) = "<p title=\"$q\">x</p>"

fun nullableText(q: String?) = "<p>$q</p>"

fun nullableNumber(q: String, m: Int?) = "<p title=\"$q\">$m</p>"

fun markup(h: Html) = "<p title=\"$h\">$h</p>"

fun trusted(t: Trusted) = "<p title=\"$t\">$t</p>"

fun number(n: Int) = "<p title=\"$n\">total $n</p>"

fun constant() = "<p title=\"$REWRITE_KIND\">$REWRITE_KIND</p>"

// Literals joined with +, each with its own dollars: a value is shown and marked as its own literal writes it.
fun joinedDollars(a: String, b: String) = $$"<p title=\"$${a}\">" + b + $$"</p>"

fun mixedDollars(b: String) = "<div>" + $$"<p>$${b}</p></div>"

// A value joined to a plain literal and a raw one: each keeps its form in the rewrite.
fun mixedForms(a: String, b: String) = "<p title=\"" + a + """">""" + b + "</p>"
