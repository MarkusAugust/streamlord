import org.intellij.lang.annotations.Language

fun p42f(a: String, b: String = "") = a + b

@Language("HTML")
fun p42a(c: Boolean): String {
    val a = run { if (c) return "i01 <p>early</p>"; "i02 x" }
    return a
}

@Language("HTML")
fun p42b(c: Boolean): String {
    val a = listOf("i03 x").map { if (c) return "i04 <p>early</p>" else it }
    return a.first()
}

@Language("HTML")
fun p42c(c: Boolean): String {
    if (c) {
        val a = if (c) "i05 x" else return "i06 <p>early</p>"
        println(a)
    }
    return "i07 <p>late</p>"
}

@Language("HTML")
fun p42d(c: Boolean): String {
    val a: String = if (c) {
        "i08 x"
    } else {
        return "i09 <p>early</p>"
    }
    return a
}

@Language("HTML")
fun p42e(c: Boolean): String {
    val a = p42f(if (c) "i10 x" else return "i11 <p>early</p>")
    return a
}

@Language("HTML")
fun p42g(c: Boolean): String {
    var a = ""
    a += if (c) "i12 x" else return "i13 <p>early</p>"
    return a
}

@Language("HTML")
val p42h: String
    get() {
        val a = if (p42h.isEmpty()) "i14 x" else return "i15 <p>early</p>"
        return a
    }

@Language("HTML")
fun p42i(c: Boolean): String = p42f(if (c) "i16 x" else return "i17 <p>early</p>")

@Language("HTML")
fun p42j(c: Boolean): String {
    val a = c.let { if (it) "i18 x" else "i19 y" }
    return a
}

fun p42k(c: Boolean): String = p42f("i20 x") + /* language=HTML */
    "i21 <p>b</p>"

fun p42l(c: Boolean): String = p42f("i22 x" -
    /* language=HTML */ "i23 <p>b</p>")

private operator fun String.minus(o: String) = this + o

fun p42m(c: Boolean): String = "i24 x" ?: /* language=HTML */
    "i25 <p>b</p>"

fun p42n(c: Boolean): String = if (c) "i26 x" else /* language=HTML */
    "i27 <p>b</p>"

fun p42o(c: Boolean): String {
    val s = /* language=HTML */
        "i28 <p>b</p>"
    return s
}

fun p42p(c: Boolean): String {
    val s =
        /* language=HTML */ "i29 <p>b</p>"
    return s
}

fun p42q(c: Boolean): String {
    val s = "i30 x" + "i31 y" +
        /* language=HTML */ "i32 <p>b</p>"
    return s
}

fun p42r(c: Boolean): String {
    val s = ("i33 x" +
        /* language=HTML */ "i34 <p>b</p>")
    return s
}

fun p42s(c: Boolean): String = when {
    c -> "i35 x" + // language=HTML
        "i36 <p>b</p>"
    else -> "i37 y"
}

fun p42t(c: Boolean): String = "i38 x" + /* language=HTML */ /* more */ "i39 <p>b</p>"

fun p42u(c: Boolean): String = p42f(/* language=HTML */

    "i40 <p>blank</p>")

fun p42v(c: Boolean): String = p42f(/* language=HTML */ /* language=HTML */ "i41 <p>twice</p>")

fun p42w(c: Boolean): String = p42f(/* language=CSS */ /* language=HTML */ "i42 <p>css then html</p>")

fun p42x(c: Boolean): String = p42f(/* language=HTML */ /* language=CSS */ "i43 <p>html then css</p>")

fun p42y(): String = p42f(/* language=HTML*/ "i44 <p>a</p>")
fun p42z(): String = p42f(/* language=HTML;x */ "i45 <p>a</p>")
fun p42za(): String = p42f(/* language=HTML,CSS */ "i46 <p>a</p>")
fun p42zb(): String = p42f(/* language=HTMLX */ "i47 <p>a</p>")
fun p42zc(): String = p42f(/* language:HTML */ "i48 <p>a</p>")
fun p42zd(): String = p42f(/* Language=HTML */ "i49 <p>a</p>")
fun p42ze(): String = p42f(/* language=HTML suffix=</div> */ "i50 <p>a</p>")
fun p42zf(): String = p42f(/* language=\tHTML */ "i51 <p>a</p>")
fun p42zg(): String = p42f(/*	language=HTML */ "i52 <p>a</p>")
