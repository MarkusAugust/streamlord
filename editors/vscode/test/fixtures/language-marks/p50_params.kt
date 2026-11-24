import org.intellij.lang.annotations.Language

fun p50a(@Language("HTML") html: String = "h01 <p>d</p>", css: String = "h02 a{}") = html + css

fun p50b(
    // language=HTML
    html: String = "h03 <p>d</p>",
    other: String = "h04 x",
) = html + other

fun p50c(
    @Language("HTML")
    html: String = "h05 <p>d</p>",
    other: String = "h06 x",
) = html + other

fun p50d(@Language("HTML") vararg parts: String) = parts.joinToString()

fun p50e(a: String = "h07 x", @Language("HTML") b: String = "h08 <p>b</p>", c: String = "h09 y") = a + b + c

fun p50f(@Language("HTML") html: String = if (true) "h10 <p>a</p>" else "h11 <p>b</p>") = html

fun p50g(@Language("HTML") html: String = buildString { append("h12 <p>x</p>") }) = html

fun p50h(@Language("HTML") html: String = "h13 <p>" + "h14 x</p>") = html

fun p50i(@Language("HTML") html: String = listOf("h15 a").first()) = html

fun p50j(@Language("HTML") html: String? = null, title: String = "h16 t") = html + title

class P50K(@Language("HTML") html: String = "h17 <p>k</p>", n: String = "h18 n") {
    constructor(x: Int) : this("h19 <p>sec</p>", "h20 s")
}

fun p50use() {
    p50a("h21 <p>call</p>", "h22 css")
    p50a(css = "h23 css", html = "h24 <p>named</p>")
    p50d("h25 <p>v1</p>", "h26 <p>v2</p>") // missed: #347
    p50e("h27 a", "h28 <p>b</p>", "h29 c")
    P50K("h30 <p>k</p>") // missed: #347
    p50b("h31 <p>b</p>")
    p50a(
        // comment
        "h32 <p>c</p>",
    )
    p50a("""
        h33 <p>raw</p>
    """.trimIndent())
}

fun p50l(cb: (@Language("HTML") String) -> Unit) = cb("h34 x")

fun p50m(f: (String) -> String = { "h35 x" }) = f("h36 y")

fun p50n(@Language("HTML") html: String = { "h37 <p>l</p>" }()) = html
