package forge.rewrite

import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate

const val REWRITE_GREETING = "hi"

// A call after one literal of a chain is that literal's alone: no rewrite, and the value says why.
fun shouted(name: String) = "<p>" + name + "</p>".uppercase()

fun whole(name: String) = "<p title=\"$name\">x</p>".uppercase()

// A value in a script stays in a script, whatever the script's text names.
fun scripted(name: String, url: String) = "<script>var s = '<style>'; var a = '$name';</script><p title=\"$url\">x</p>"

// A Trusted is accepted wherever it lands, so the other value gets the rewrite.
fun trustedStyle(css: String, name: String) = "<style>${Trusted(css)}</style><p title=\"$name\">x</p>"

fun trustedAttribute(t: Trusted, name: String) = "<p lang=\"$t\" title=\"$name\">x</p>"

// trimMargin as it is mostly written, and with a margin given by name.
fun margined(name: String) = """
    |<div id="$name">
    |</div>
    """.trimMargin()

fun namedMargin(name: String) = """
    ><div id="$name">
    ></div>
    """.trimMargin(marginPrefix = ">")

// Kotlin takes a constant here, and a string whose values need no escaping has nothing to rewrite.
@Suppress("<p>$REWRITE_GREETING</p>")
fun annotated(count: Int) = "<p>total $count</p>"

// A call or an if in element text may give markup, which the rewrite would escape.
fun listed(flag: Boolean, name: String, url: String) = "<ul>${if (flag) "<li>$name</li>" else ""}</ul><a href=\"/u/$url\">x</a>"

// One value alone that may be a List.
fun tags(tags: List<String>) = "<p>$tags</p>"

// A value before the first literal, and the URL of a refresh.
fun leading(name: String, url: String) = name + "<p>" + url + "</p>"

fun refresh(url: String) = "<meta http-equiv=\"refresh\" content=\"0;url=$url\">"

fun twoInStyle(css: String, more: String) = "<style>$css $more</style>"
