import org.intellij.lang.annotations.Language

@Language(value = "HTML")
val p27a = "b1 <b>x</b>"

@Language("HTML", prefix = "<div>", suffix = "</div>")
val p27b = "b2 <b>x</b>"

@org.intellij.lang.annotations.Language("HTML")
val p27c = "b3 <b>x</b>"

@Language("html")
val p27d = "b4 <b>x</b>"

//language=HTML
val p27e = "b5 <b>x</b>"

// language=HTML prefix=<div> suffix=</div>
val p27f = "b6 <b>x</b>"

// language=html
val p27g = "b7 <b>x</b>"

@Language("HTML")
val p27h = p27wrap("b8 <b>x</b>")

@Language("HTML")
val p27i = p27wrap("b9 <b>x</b>", "c1 y")

@Language("HTML")
val p27j = "c2 <b>x</b>".uppercase()

@Language("HTML")
val p27k = listOf("c3 a").joinToString()

@Language("HTML")
val p27l = mapOf("c4 k" to "c5 v").toString()

@Language("HTML")
val p27m: String
    get() = "c6 <b>x</b>"

@Language("HTML")
val p27n: String
    get() {
        return "c7 <b>x</b>"
    }

@Language("HTML")
val p27o = 1
val p27p = "c8 x"

@Language("HTML")
fun p27q() = Unit
val p27r = "c9 x"

@Language("HTML")
fun p27s(@Language("HTML") a: String = "d1 <b>x</b>", b: String = "d2 x") = a + b

fun p27t(a: String = "d3 x", @Language("HTML") b: String = "d4 <b>x</b>") = a + b

fun p27wrap(vararg s: String): String = s.joinToString()

fun p27u() {
    p27t(b = "d5 <b>x</b>")
    p27t("d6 x", "d7 <b>x</b>")
    p27s("d8 <b>x</b>")
}

@Language("HTML")
val p27v = if (p27a.isEmpty()) "d9 <b>x</b>" else "e1 <i>y</i>"

@Language("HTML")
val p27w = when (p27a.length) {
    1 -> "e2 <b>x</b>"
    else -> "e3 <i>y</i>"
}

@Language("HTML")
val p27x = p27a.ifEmpty { "e4 <b>x</b>" }

@Language("HTML")
val p27y = p27a.takeIf { it.isNotEmpty() } ?: "e5 <b>x</b>"

@Language("HTML")
val p27z = "e6 <b>" + p27a + "e7 </b>"

@Language("HTML")
val p27aa = p27a + "e8 </b>"

@Language("HTML")
val p27ab by lazy { "e9 <b>x</b>" }

@Language("HTML")
val p27ac = run { "f1 <b>x</b>" }

@Language("HTML")
val p27ad = buildString { append("f2 <b>x</b>") }

@Language("HTML")
val p27ae = StringBuilder().append("f3 <b>x</b>").toString()

@Language("HTML")
val p27af = """
    |f4 <b>x</b>
""".trimMargin()

@Language("HTML")
val p27ag = "f5 <b>${p27a}</b> ${"f6 inner"}"

@Language("HTML")
val p27ah = ("f7 <b>x</b>")

@Language("HTML")
val p27ai = p27a.let { "f8 <b>$it</b>" }

@Language("HTML")
val p27aj = p27a.ifBlank { p27wrap("f9 <b>") }

@Language("HTML")
val p27ak = p27wrap(if (true) "g1 <b>" else "g2 <i>")
