import org.intellij.lang.annotations.Language

annotation class P51Ann(val v: String = "")

@Language("HTML")
fun p51a(c: String?): String {
    val x = c?.let { return "j01 <p>let</p>" } ?: "j02 y"
    val y = listOf(1).map { if (it > 0) return "j03 <p>map</p>"; "j04 z" }
    var z = "j05 a"
    z = if (c == null) return "j06 <p>assign</p>" else "j07 b"
    return x + y + z
}

@Language("HTML")
fun p51b(c: String?): String {
    val x: String
    if (c == null) return "j08 <p>a</p>"
    x = c
    val obj = object {
        fun f(): String = "j09 obj"
    }
    return x + obj.f()
}

fun p51c(): String {
    val a = "j10 x" + /* language=HTML */
        "j11 <p>b</p>"
    val b = "j12 x" /* language=HTML */ + "j13 <p>c</p>"
    val c = "j14 x".plus(/* language=HTML */ "j15 <p>d</p>")
    return a + b + c
}

fun p51d() {
    // language=HTML
    @P51Ann("j16 v") // missed: #347
    val a = "j17 <p>a</p>"
    // language=HTML
    @P51Ann(
        "j18 v", // missed: #347
    )
    val b = "j19 <p>b</p>"
    // language=HTML
    @Suppress("UNUSED_VARIABLE") val c = "j20 <p>c</p>" // missed: #347
    println(a + b + c)
}

fun p51e() {
    // language=HTML
    val a = mapOf(
        "j21 <p>k</p>" to "j22 <p>v</p>",
    )
    // language=HTML
    val b = arrayOf("j23 <p>x</p>").map { it + "j24 y" }
    println(a.toString() + b)
}

fun p51f() {
    // language=HTML
    val a = "j25 <p>a</p>"
        .replace("j26 x", "j27 y")
    // language=HTML
    val b = "j28 <p>b</p>"
        + "j29 c"
    println(a + b)
}

class P51G {
    // language=HTML
    @P51Ann
    val a = "j30 <p>a</p>"

    @P51Ann // language=HTML
    val b = "j31 <p>b</p>"

    // language=HTML
    private val c = "j32 <p>c</p>"

    // language=HTML
    @Volatile var d = "j33 <p>d</p>"

    // language=CSS
    val e = "j34 a{}"
    val f = "j35 f"
}

fun p51h(c: Boolean) {
    // language=HTML
    val a = if (c) {
        val inner = "j36 <p>i</p>"
        inner
    } else "j37 <p>e</p>"
    println(a)
    // language=HTML
    val b = c.let {
        fun g() = "j38 <p>g</p>"
        g()
    }
    println(b)
}

@Language("HTML")
fun p51i(): String {
    // language=CSS
    val css = "j39 a{}"
    return "j40 <style>$css</style>"
}

@Language("HTML")
fun p51j(): String {
    // a note
    return "j41 <p>noted</p>"
}

@Language("HTML")
fun p51k(): String = // note
    "j42 <p>noted</p>"

@Language("HTML")
fun p51l(): String = /* note */ "j43 <p>noted</p>"

@Language("HTML")
// a comment between
fun p51m(): String = "j44 <p>m</p>"

@Language("HTML") // trailing
fun p51n(): String = "j45 <p>n</p>"

@Language("HTML")
fun p51o(): String {
    return /* c */ "j46 <p>o</p>"
}

@Language("HTML")
fun p51p(n: Int): String {
    when (n) {
        0 -> return "j47 <p>0</p>"
        else -> {
            val s = "j48 x"
            return "j49 <p>$s</p>"
        }
    }
}

@Language("HTML")
fun p51q(): String {
    val list = mutableListOf<String>()
    list += "j50 <p>a</p>"
    list.add("j51 <p>b</p>")
    return list.joinToString("j52 ")
}

fun p51r(): String {
    // language=HTML
    val s = "j53 <p>a</p>"
    return s
}

fun p51s() {
    // language=HTML
    val a = "j54 <p>a</p>"
    // unrelated note
    val b = "j55 b"
    println(a + b)
}

fun p51t() {
    // language=HTML
    val (a, b) = Pair("j56 <p>a</p>", "j57 <p>b</p>")
    println(a + b)
}

fun p51u() {
    // language=HTML
    for (s in listOf("j58 <p>a</p>")) println(s + "j59 x")
}
