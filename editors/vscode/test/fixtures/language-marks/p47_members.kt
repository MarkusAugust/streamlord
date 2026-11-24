import org.intellij.lang.annotations.Language

interface P47Widget {
    @Language("HTML")
    fun render(): String = "e01 <div>w</div>"

    @Language("HTML")
    val tag: String
        get() = "e02 <span>t</span>"
}

abstract class P47Base {
    @Language("HTML")
    abstract fun body(): String

    @Language("HTML")
    open fun head(): String {
        return "e03 <head></head>"
    }
}

class P47Page(
    @Language("HTML") val intro: String = "e04 <p>intro</p>",
    val title: String = "e05 title",
    @param:Language("HTML") val outro: String = "e06 <p>outro</p>",
) : P47Base() {
    override fun body(): String = "e07 <body></body>"

    @Language("HTML")
    override fun head(): String = "e08 <head>x</head>"

    @Language("HTML")
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("e09 <p>")
        return sb.append("e10 </p>").toString()
    }

    @Language("HTML")
    private inline fun <reified T> typed(x: T): String = "e11 <p>$x</p>"

    @Language("HTML")
    fun <T> where1(x: T): String where T : Any {
        return "e12 <p>$x</p>"
    }

    @Language("HTML")
    suspend fun later(): String = "e13 <p>later</p>"

    @Language(value = "HTML")
    fun named(): String = "e14 <p>named</p>"

    @Language("html")
    fun lower(): String = "e15 <p>lower</p>"

    @field:Language("HTML")
    val field1 = "e16 <p>field</p>"

    @property:Language("HTML")
    val prop1 = "e17 <p>prop</p>"

    @Language("HTML")
    var mutable: String = "e18 <p>m</p>"
        set(v) {
            field = v + "e19 x" // missed: #347
        }

    @Language("HTML")
    val withGetterBlock: String
        get() {
            if (title.isEmpty()) return "e20 <p>empty</p>"
            return "e21 <p>$title</p>"
        }

    /**
     * Docs.
     */
    @Language("HTML")
    fun documented(): String = "e22 <p>doc</p>"

    @Language("HTML") fun sameLine(): String = "e23 <p>same</p>"

    @Language("HTML")
    fun exprNextLine(): String =
        "e24 <p>next</p>"

    @Language("HTML")
    fun exprIf(c: Boolean): String =
        if (c) "e25 <p>a</p>" else "e26 <p>b</p>"

    @Language("HTML")
    fun exprWhen(n: Int): String =
        when (n) {
            1 -> "e27 <p>1</p>"
            else -> "e28 <p>n</p>"
        }

    @Language("HTML")
    fun exprLet(s: String?): String = s?.let { "e29 <p>$it</p>" } ?: "e30 <p>none</p>"

    @Language("HTML")
    fun exprRun(): String = run { "e31 <p>run</p>" }

    fun unmarkedAfter(): String = "e32 plain"
}

fun P47Page.ext(): String = "e33 plain"

@Language("HTML")
fun P47Page.extMarked(): String = "e34 <p>${title}</p>"

@Language("HTML")
fun String.extOnString(): String = "e35 <b>$this</b>"

data class P47Row(@Language("HTML") val cell: String = "e36 <td>c</td>", val n: Int = 0)

enum class P47Kind(@Language("HTML") val html: String) {
    A("e37 <p>a</p>"), // missed: #347
    B("e38 <p>b</p>"), // missed: #347
}

fun p47use() {
    P47Row("e39 <td>x</td>") // missed: #347
    P47Row(cell = "e40 <td>y</td>") // missed: #347
    P47Page(intro = "e41 <p>i</p>", title = "e42 t") // missed: #347
}
