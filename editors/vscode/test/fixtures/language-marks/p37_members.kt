import org.intellij.lang.annotations.Language

class P37View(val name: String) {
    @get:Language("HTML")
    val header: String
        get() = "d01 <h1>$name</h1>"

    val footer: String
        @Language("HTML")
        get() {
            return "d02 <footer>$name</footer>"
        }

    @Language("HTML")
    val body: String
        get() = if (name.isEmpty()) "d03 <p>none</p>" else "d04 <p>$name</p>"

    @Language("HTML")
    fun render(): String = when (name) {
        "d05 k" -> "d06 <b>k</b>"
        else -> "d07 <i>$name</i>"
    }

    @Language("HTML")
    override fun toString(): String {
        return "d08 <span>$name</span>"
    }

    val plain: String get() = "d09 <x>"

    companion object {
        @Language("HTML")
        const val EMPTY = "d10 <p>empty</p>"

        @Language("HTML")
        fun of(s: String?): String {
            return s ?: EMPTY
        }

        @Language("HTML")
        fun wrap(s: String): String {
            val t = s.trim()
            return "d11 <div>$t</div>"
        }

        // language=HTML
        val LOADING = "d12 <p>loading</p>"

        val NOTMARKED = "d13 <p>x</p>"
    }

    object Inner {
        @Language("HTML")
        fun x(c: Boolean): String = if (c) "d14 <a>" else "d15 <b>"
    }
}

interface P37Renderer {
    @Language("HTML")
    fun render(): String = "d16 <p>default</p>"

    @Language("HTML")
    fun other(c: Boolean): String {
        if (c) return "d17 <p>c</p>"
        return "d18 <p>d</p>"
    }
}

object P37Obj : P37Renderer {
    override fun render(): String = "d19 <p>override</p>"
}

enum class P37Kind(@Language("HTML") val html: String) {
    A("d20 <p>a</p>"), // missed: #347
    B("d21 <p>b</p>"); // missed: #347

    @Language("HTML")
    fun label(): String = when (this) {
        A -> "d22 <b>a</b>"
        B -> "d23 <b>b</b>"
    }
}

class P37Ctor(@Language("HTML") val h: String = "d24 <p>default</p>", val other: String = "d25 <p>x</p>")

fun P37View.ext(): String = "d26 <p>ext</p>"

@Language("HTML")
fun P37View.ext2(): String = "d27 <p>${this.name}</p>"

val P37View.extProp: String
    @Language("HTML") get() = "d28 <p>prop</p>"

@Language("HTML")
val p37lambda: () -> String = { "d29 <p>lambda</p>" }

@Language("HTML")
val p37lambda2 = { c: Boolean -> if (c) "d30 <p>a</p>" else "d31 <p>b</p>" }

@Language("HTML")
fun p37run(): String = run { "d32 <p>run</p>" }

@Language("HTML")
fun p37runReturn(c: Boolean): String = run {
    if (c) return "d33 <p>early</p>"
    "d34 <p>late</p>"
}

@Language("HTML")
fun p37anon(): String {
    val f = fun(): String { return "d35 inner" }
    return f()
}

@Language("HTML")
fun p37anon2(): String {
    val f = fun(): String = "d36 inner"
    return "d37 <p>${f()}</p>"
}

@Language("HTML")
fun p37localFun(): String {
    fun inner(): String = "d38 inner"
    return inner()
}

@Language("HTML")
fun p37localClass(): String {
    class L { fun x() = "d39 inner" }
    return L().x()
}

@Language("HTML")
fun p37objectExpr(): String {
    val o = object { fun x(): String { return "d40 inner" } }
    return o.x()
}

fun p37lambdaParam(f: (String) -> String) = f("d41 x")

@Language("HTML")
fun p37lambdaArg(): String = p37lambdaParam { "d42 inner $it" }
