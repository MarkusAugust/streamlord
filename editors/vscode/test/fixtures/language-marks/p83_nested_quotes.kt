import org.intellij.lang.annotations.Language

@Language("HTML")
fun p83a(user: String, admin: Boolean): String =
    "Q40 <div class=\"${if (admin) "Q41 admin" else "Q42 user"}\">${user.ifEmpty { "Q43 nobody" }}</div>"

@Language("HTML")
fun p83b(attrs: Map<String, String>): String =
    "Q44 <input ${attrs.entries.joinToString(" ") { (k, v) -> "Q45 $k=\"$v\"" }}>"

// language=HTML
val p83c = "Q46 <a href=\"${"Q47 /path"}\">link</a>"

@Language("HTML")
val p83d = "Q48 <p>${"Q49 \"quoted\""}</p>"

fun p83e(): String {
    // language=HTML
    return "Q50 <p data-on:click=\"@get('/x')\">${listOf("Q51 a").first()}</p>"
}
