import org.intellij.lang.annotations.Language

@Language("HTML")
fun p97a(a: String, b: Int) = "R45 <p>${a}</p><p>${b + 1}</p><p>${"R46 lit"}</p><p>${'x'}</p>"

@Language("HTML")
fun p97b(m: Map<String, String>) = "R47 <p>${m["R48 key"]}</p>"

@Language("HTML")
fun p97c(xs: List<Int>) = "R49 <p>${xs.filter { it > 0 }.map { "R50 <i>$it</i>" }}</p>"

@Language("HTML")
fun p97d() = "R51 <p>\$notATemplate \"q\" \\ \n</p>"

@Language("HTML")
fun p97e() = """R52 <p>"quoted" and ${"R53 \"esc\""} and '''</p>"""

@Language("HTML")
fun p97f(x: Any) = "R54 <p>${when (x) { is String -> "R55 s"; else -> "R56 o" }}</p>"

@Language("HTML")
fun p97g(t: String) = "R57 <p>${t.let { s -> "R58 ${s.length}" }}</p>"

@Language("HTML")
fun p97h(t: String) = "R59 <p>${ object { override fun toString() = "R60 anon" } }</p>"
