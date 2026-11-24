import org.intellij.lang.annotations.Language

fun p40f(a: String, b: String = "") = a + b

@Language("HTML")
fun p40a(c: Boolean): String {
    val a = if (c) "g01 x" else return "g02 <p>early</p>"
    return a
}

@Language("HTML")
fun p40b(c: Int): String {
    val a = when (c) { 1 -> "g03 x"; else -> return "g04 <p>early</p>" }
    return a
}

@Language("HTML")
fun p40c(c: String?): String {
    val a = c ?: if (c == null) return "g05 <p>early</p>" else "g06 x"
    return a
}

@Language("HTML")
fun p40d(c: Boolean): String {
    println(if (c) "g07 x" else return "g08 <p>early</p>")
    return "g09 <p>late</p>"
}

@Language("HTML")
fun p40e(c: Boolean): String {
    val a = try { "g10 x" } catch (e: Exception) { return "g11 <p>early</p>" }
    return a
}

@Language("HTML")
fun p40g(c: String?): String {
    val a = p40f(c ?: return "g12 <p>early</p>")
    return a
}

@Language("HTML")
fun p40h(c: Boolean): String {
    var a = "g13 x"
    a = if (c) "g14 y" else return "g15 <p>early</p>"
    return a
}

fun p40i(c: Boolean): String = when {
    c -> "g16 x" + /* language=HTML */
        "g17 <p>b</p>"
    else -> "g18 <p>c</p>"
}

fun p40j(c: Boolean): String = "g19 x" + /* language=HTML */
    "g20 <p>b</p>"

fun p40k(c: Boolean): String {
    val s = "g21 x" +
        /* language=HTML */
        "g22 <p>b</p>"
    return s
}

fun p40l(c: Boolean): String {
    val s = "g23 x" +
        // language=HTML
        "g24 <p>b</p>"
    return s
}

fun p40m(c: Boolean): String = p40f("g25 x" + /* language=HTML */
    "g26 <p>b</p>")

fun p40n(c: Boolean): String = p40f("g27 x",
    /* language=HTML */ "g28 <p>b</p>")

fun p40o(): String = p40f(/* language = HTML */ "g29 <p>spaced</p>")
fun p40p(): String = p40f(/*language =HTML*/ "g30 <p>spaced</p>")
fun p40q(): String = p40f(/* language= HTML */ "g31 <p>spaced</p>")
fun p40r(): String = p40f(/* language=HTML  */ "g32 <p>trailing</p>")
fun p40s(): String = p40f(/*  language=HTML */ "g33 <p>leading</p>")

fun p40t(): String {
    // language = HTML
    return "g34 <p>spaced</p>"
}

fun p40u(): String {
    //language=HTML
    return "g35 <p>tight</p>"
}

fun p40v(): String {
    // language= HTML
    val s = "g36 <p>spaced</p>"
    return s
}

val p40w = /* language = HTML */ "g37 <p>spaced</p>"

fun p40x(): String = p40f(/* language=HTML */ /* other */ "g38 <p>two</p>")
fun p40y(): String = p40f(/* language=HTML */ /** doc */ "g39 <p>two</p>")
val p40z = /* language=HTML */ /* other */ "g40 <p>two</p>"
fun p40aa(c: Boolean): String = if (c) /* language=HTML */ /* x */ "g41 <p>two</p>" else "g42 y"

fun p40bb(): String {
    // language=HTML
    /* other */
    return "g43 <p>two</p>"
}

fun p40cc(): String {
    /* language=HTML */ /* other */
    return "g44 <p>two</p>"
}

fun p40dd(): String {
    // language=HTML
    // other
    val s = "g45 <p>two</p>"
    return s
}

fun p40ee(c: Boolean): String = when {
    // language=HTML
    // other
    c -> "g46 <p>two</p>"
    else -> "g47 y"
}

fun p40ff(): String = p40f(/* language=HTML */ // trailing
    "g48 <p>two</p>")

fun p40gg(): String = p40f(// language=HTML
    "g49 <p>line</p>")

fun p40hh(): String = p40f(
    "g50 x", // language=HTML
    "g51 <p>b</p>",
)

fun p40ii(c: Boolean): String = when {
    c -> "g52 x" // language=HTML
    else -> "g53 <p>b</p>"
}

fun p40jj(c: Boolean): String {
    val s = "g54 x" /* language=HTML */
    val t = "g55 <p>b</p>"
    return s + t
}
