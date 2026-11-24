import org.intellij.lang.annotations.Language

data class P85User(val name: String, val email: String)

@Language("HTML")
fun P85User.toHtml(): String = "Q60 <dl><dt>$name</dt><dd>$email</dd></dl>"

@Language("HTML")
fun List<P85User>.toTable(): String = joinToString(separator = "Q61 ", prefix = "Q62 <table>", postfix = "Q63 </table>") { it.toHtml() }

@Language("HTML")
fun String.wrapIn(tag: String): String = "Q64 <$tag>$this</$tag>"

fun String.plainExt(): String = "Q65 $this!"

@get:Language("HTML")
val P85User.link: String
    get() = "Q66 <a href=\"mailto:$email\">$name</a>"

@Language("HTML")
val P85User.short get() = "Q67 <b>$name</b>"
