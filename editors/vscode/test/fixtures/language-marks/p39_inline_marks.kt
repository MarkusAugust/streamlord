import org.intellij.lang.annotations.Language

fun p39f(a: String, b: String = "") = a + b
fun p39g(vararg a: String) = a.joinToString()

val p39a = /* language=HTML */ "f01 <p>a</p>"
val p39b: String = /* language=HTML */ "f02 <p>b</p>"
val p39c = /* language=HTML */ """
    f03 <p>c</p>
""".trimIndent()
val p39d = /* language=HTML */ "f04 <p>d</p>".trim()
val p39e = p39f(/* language=HTML */ "f05 <p>e</p>", "f06 <p>x</p>")
val p39h = p39f("f07 x", /* language=HTML */ "f08 <p>h</p>")
val p39i = p39f(b = /* language=HTML */ "f09 <p>i</p>", a = "f10 <p>x</p>")
val p39j = p39g(/* language=HTML */ "f11 <p>j</p>", "f12 <p>k</p>", /* language=HTML */ "f13 <p>l</p>")
val p39k = p39f(
    /* language=HTML */
    "f14 <p>k</p>",
    "f15 <p>x</p>",
)
val p39l = p39f(
    // language=HTML
    "f16 <p>l</p>",
    "f17 <p>x</p>",
)
val p39m = mapOf("f18 key" to /* language=HTML */ "f19 <p>m</p>")
val p39n = listOf("f20 x").map { /* language=HTML */ "f21 <p>$it</p>" }
val p39o = listOf("f22 x").map { it -> /* language=HTML */ "f23 <p>$it</p>" }
val p39p = arrayOf(/* language=HTML */ "f24 <p>p</p>")
val p39q = "f25 x".ifEmpty { /* language=HTML */ "f26 <p>q</p>" }
val p39r = p39f(/* language=HTML */ "f27 <p>r</p>" + "f28 <p>s</p>")
val p39s = p39f("f29 x" + /* language=HTML */ "f30 <p>s</p>")
val p39t = p39f(/* language=HTML */ p39a, "f31 <p>t</p>") // missed: #347
val p39u = p39f(/* language=HTML */ p39f("f32 <p>u</p>"))
val p39v = p39f(/* language=HTML */ ("f33 <p>v</p>"))
val p39w = p39f(/* language=HTML */ if (true) "f34 <p>w</p>" else "f35 <p>x</p>")
val p39x = p39f(/* language=HTML */ "f36 <p>x</p>", /* language=CSS */ "f37 <p>y</p>")
val p39y = p39f(/* language=HTML */ "f38 <p>$p39a</p>")
val p39z = p39f(/* language=HTML */ "f39 <p>z</p>")+"f40 <p>tail</p>"

class P39Holder {
    var h: String = ""
    fun set(c: Boolean) {
        h = /* language=HTML */ "f41 <p>h</p>"
        h = if (c) /* language=HTML */ "f42 <p>a</p>" else "f43 <p>b</p>"
        this.h = /* language=HTML */ "f44 <p>this</p>"
    }
}

fun p39aa(c: Boolean) {
    var local = ""
    local = /* language=HTML */ "f45 <p>local</p>"
    local += /* language=HTML */ "f46 <p>plus</p>"
    println(local)
}

fun p39bb(c: Boolean): String {
    val t = p39f(/* language=HTML */ "f47 <p>a</p>")
    return t
}

fun p39cc(c: Boolean): String = when {
    c -> p39f(/* language=HTML */ "f48 <p>a</p>")
    else -> p39f("f49 <p>b</p>")
}

fun p39dd(c: Boolean): String = if (c) p39f("f50 x", /* language=HTML */ "f51 <p>a</p>") else "f52 <p>b</p>"

fun p39ee(s: String?): String = s ?: p39f(/* language=HTML */ "f53 <p>n</p>")

fun p39ff(s: String?): String = p39f(s ?: /* language=HTML */ "f54 <p>n</p>")

fun p39gg(): String = p39f(/* language=HTML */
    "f55 <p>next line</p>")

fun p39hh(): String = p39f(/*language=HTML*/"f56 <p>tight</p>")

fun p39ii(): String = p39f(/* language = HTML */ "f57 <p>spaced</p>")

fun p39jj(): String = p39f(/** language=HTML */ "f58 <p>kdoc</p>") // missed: #347

fun p39kk(): String = p39f(/* language=html */ "f59 <p>lower</p>")

fun p39ll(): String = p39f(/* language=HTML prefix=<div> */ "f60 <p>prefix</p>")

fun p39mm(): String = p39f(/* language=HTML */ /* other */ "f61 <p>two comments</p>")

fun p39nn(): String = p39f(/* other */ /* language=HTML */ "f62 <p>two comments</p>")

fun p39oo(c: Boolean): String = when {
    c -> "f63 x"
    else -> /* language=HTML */ "f64 <p>a</p>" + "f65 <p>b</p>" // missed: #347
}

fun p39pp(c: Boolean): String = when {
    c -> /* language=HTML */ "f66 x" + "f67 y" // missed: #347
    else -> "f68 <p>b</p>"
}

fun p39qq(c: Boolean): String = when {
    c -> /* language=HTML */ "f69 <p>a</p>"
    else
    -> "f70 <p>b</p>"
}

fun p39rr(c: Boolean): String = when {
    c -> "f71 x" + /* language=HTML */
        "f72 <p>b</p>"
    else -> "f73 <p>c</p>"
}

fun p39ss(c: Boolean, s: String): String = when (s) {
    /* language=HTML */ "f74 key" -> "f75 <p>a</p>"
    else -> "f76 <p>b</p>"
}

fun p39tt(c: Boolean): String = when { c -> /* language=HTML */ "f77 <p>a</p>" else -> "f78 <p>b</p>" }
