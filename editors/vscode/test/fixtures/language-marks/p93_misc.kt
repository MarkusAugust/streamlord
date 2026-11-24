import org.intellij.lang.annotations.Language

enum class P93Icon(@Language("HTML") val svg: String) {
    Check("Q161 <svg><path/></svg>"), // missed: #347
    Cross("Q162 <svg><line/></svg>"); // missed: #347

    @Language("HTML")
    fun wrapped() = "Q163 <span>$svg</span>"
}

interface P93Renderer {
    @Language("HTML")
    fun render(): String = "Q164 <div>default</div>"
}

typealias P93Html = String

@Language("HTML")
val p93lambda: (String) -> String = { "Q165 <p>$it</p>" }

@Language("HTML")
val p93fun = fun(s: String): String { return "Q166 <p>$s</p>" }

val p93map = mapOf(
    "Q167 key" to "Q168 <p>value</p>",
)

@Language("HTML")
val p93pair = "Q169 <a>" to "Q170 </a>"

@Language("HTML")
val p93list = listOf("Q171 <li>1</li>", "Q172 <li>2</li>")

fun p93local(): String {
    @Language("HTML")
    fun inner(n: Int) = "Q173 <i>$n</i>"
    return inner(1) + "Q174 tail"
}

@Language("HTML")
val p93nullable: String? = null ?: "Q175 <p>fallback</p>"

@Suppress("Q176 unused")
@Language("HTML")
val p93suppressed = "Q177 <p>suppressed</p>"

@Language(value = "HTML")
val p93named = "Q178 <p>named arg</p>"

@field:Language("HTML")
val p93field = "Q179 <p>field</p>"

@Language("HTML" + "")
val p93concat = "Q180 <p>concat</p>"
