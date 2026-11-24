import org.intellij.lang.annotations.Language

fun p90html(@Language("HTML") s: String): String = s
fun p90two(a: String, @Language("HTML") b: String) = a + b
fun p90vararg(@Language("HTML") vararg parts: String) = parts.joinToString()
class P90Sse { fun patchElements(@Language("HTML") elements: String) {} }

fun p90use(sse: P90Sse, n: Int) {
    p90html("Q119 <p>$n</p>")
    p90two("P900 first", "P901 <b>second</b>")
    p90two(b = "P902 <i>named</i>", a = "P903 a")
    p90vararg("P904 <a>", "P905 </a>") // missed: #347
    sse.patchElements("P906 <div id=\"c\">$n</div>") // missed: #347
    sse.patchElements(
        """
        P907 <div id="d">
          ${if (n > 1) "P908 many" else "P909 one"}
        </div>
        """.trimIndent(), // missed: #347
    )
    with(sse) { patchElements("Q130 <span>w</span>") } // missed: #347
    sse.apply { patchElements("Q131 <span>a</span>") } // missed: #347
    p90html(if (n > 0) "Q132 <p>pos</p>" else "Q133 neg")
    p90html("Q134 <p>" + n + "Q135 </p>") // missed: #347
}
