import org.intellij.lang.annotations.Language

class P23Page(val title: String) {
    @Language("HTML")
    val head = "E1 <title>$title</title>"

    @Language("HTML")
    private val body: String = "E2 <body>$title</body>"

    @Language("HTML")
    val lazyBody by lazy { "E3 <p>$title</p>" }

    @Language("HTML")
    var mutable = "E4 <p>"

    @Language("HTML")
    lateinit var late: String

    val plain = "E5 not html"

    fun render(): String {
        late = "E6 <p>$title</p>" // missed: #347
        mutable = "E7 <p>" + title // missed: #347
        mutable += "E8 </p>" // missed: #347
        val local = "E9 not html"
        return local
    }

    companion object {
        @Language("HTML")
        const val TPL = "F1 <b>x</b>"

        @Language("HTML")
        val TPL2 = """
            F2 <b>x</b>
        """.trimIndent()

        val notMarked = "F3 x"

        @JvmStatic
        @Language("HTML")
        fun make(n: String) = "F4 <b>$n</b>"
    }

    inner class Inner {
        @Language("HTML")
        val inner = "F5 <i>x</i>"
        val plain2 = "F6 x"
    }

    class Nested {
        @Language("HTML")
        var nested = ""
        fun f() {
            nested = "F7 <i>x</i>" // missed: #347
        }
    }

    object Obj {
        @Language("HTML")
        val o = "F8 <i>x</i>"
        var v = ""
        fun g() {
            v = "F9 x"
        }
    }
}

@Language("HTML")
val String.p23ext: String
    get() = "G1 <b>$this</b>"

@Language("HTML")
val P23Page.p23ext2: String
    get() {
        val s = "G2 <b>x</b>"
        return s
    }

val P23Page.p23ext3: String
    @Language("HTML")
    get() = "G3 <b>$title</b>"

@field:Language("HTML")
val p23field = "G4 <b>x</b>"

@get:Language("HTML")
val p23get = "G5 <b>x</b>"

@Language("HTML") val p23inline = "G6 <b>x</b>"

@Language("HTML")
internal val p23typed: String? = "G7 <b>x</b>"

data class P23Data(@Language("HTML") val html: String = "G8 <b>x</b>", val other: String = "G9 x")

class P23Ctor(@Language("HTML") var html: String) {
    fun f() {
        html = "H1 <b>x</b>" // missed: #347
    }
}

fun p23use() {
    val p = P23Page("H2 t")
    p.mutable = "H3 <b>x</b>" // missed: #347
    P23Data(html = "H4 <b>x</b>") // missed: #347
    P23Data("H5 <b>x</b>", "H6 x") // missed: #347
    P23Ctor("H7 <b>x</b>").html = "H8 <b>x</b>" // missed: #347
}
