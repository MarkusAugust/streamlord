import org.intellij.lang.annotations.Language

@Language("HTML")
var p28page = ""

fun p28w(s: String): String? = s

@Language("HTML")
fun p28a(): String {
    return "k1 <b>x</b>".trimIndent()
}

@Language("HTML")
fun p28b(): String {
    return """
        |k2 <b>x</b>
    """.trimMargin()
}

@Language("HTML")
fun p28c(): String {
    return "k3 <b>x</b>".trim()
}

@Language("HTML")
fun p28d(): String {
    return "k4 <b>%s</b>".format(1)
}

@Language("HTML")
fun p28e(c: Boolean): String {
    if (c) return """
        k5 <b>x</b>
    """.trimIndent()
    return "k6 <i>y</i>"
}

@Language("HTML")
fun p28f(): String {
    val s = "k7 <b>x</b>".trimMargin()
    return s
}

@Language("HTML")
fun p28g(n: String): String {
    val s = n + "k8 <b>" + n
    return s
}

@Language("HTML")
fun p28h(n: String): String {
    val s = n + n + "k9 <b>"
    return s
}

@Language("HTML")
fun p28i(n: String): String {
    val s = "l1 <b>".trimIndent() + n
    return s
}

@Language("HTML")
fun p28j(n: String): String {
    val s = n.trim() + "l2 <b>"
    return s
}

@Language("HTML")
fun p28k(n: String): String {
    val s = p28w("l3 <b>") + "l4 x"
    return s
}

@Language("HTML")
val p28l: String
    get() {
        return """
            l5 <b>x</b>
        """.trimIndent()
    }

class P28M {
    @Language("HTML")
    fun f(): String {
        return """
            l6 <b>x</b>
        """.trimIndent()
    }

    @Language("HTML")
    fun g(): String {
        val s = """
            l7 <b>x</b>
        """.trimIndent()
        return s
    }
}

fun p28n() {
    // language=HTML
    val s = """
        l8 <b>x</b>
    """.trimIndent()
}

fun p28o(): String {
    // language=HTML
    return """
        l9 <b>x</b>
    """.trimIndent()
}

fun p28p(m: Map<String, String>, xs: List<String>) {
    p28page = m["m1 k"] ?: "m2 x"
    p28page = xs[0] ?: "m3 x"
    p28page = p28w("m4 a")!! + "m5 b" // missed: #347
    p28page = p28w("m6 a")?.trim() ?: "m7 b"
    p28page = p28w("m8 a").toString() + "m9 b" // missed: #347
    p28page = p28w("n1 a")!!.trim() // missed: #347
    p28page = (p28w("n2 a")) ?: "n3 b"
    p28page = p28w("n4 a") as String + "n5 b" // missed: #347
    p28page = listOf("n6 a")[0] + "n7 b" // missed: #347
    p28page = listOf("n8 a").first() + "n9 b" // missed: #347
    p28page = mapOf("o1 a" to "o2 b").getValue("o3 k") // missed: #347
    p28page = mapOf("o4 a" to "o5 b")["o6 k"]!! // missed: #347
}

fun p28q(c: Boolean, n: String?) {
    p28page = "p1 <b>" + when { c -> "p2 x" else -> "p3 y" } // missed: #347
    p28page = n ?: if (c) "p4 x" else "p5 y"
    p28page = n ?: when { c -> "p6 x" else -> "p7 y" }
    p28page = if (c) "p8 x" else "p9 y" + "q1 z" // missed: #347
    p28page = "q2 <b>" + try { "q3 x" } catch (e: Exception) { "q4 y" } // missed: #347
    p28page = n + if (c) "q5 x" else "q6 y"
    p28page = "q7 <b>${if (c) "q8 x" else "q9 y"}</b>" // missed: #347
}

@Language("HTML")
fun p28r(c: Boolean): String {
    return "r1 <b>${if (c) "r2 x" else "r3 y"}</b>"
}

@Language("HTML")
fun p28s(c: Boolean, n: String?): String {
    return "r4 <b>" + if (c) "r5 x" else "r6 y"
}

@Language("HTML")
fun p28t(c: Boolean, n: String?): String {
    return n ?: if (c) "r7 x" else "r8 y"
}

@Language("HTML")
fun p28u(c: Boolean, n: String?): String {
    val s = "r9 <b>" + if (c) "s1 x" else "s2 y"
    return s
}

@Language("HTML")
val p28v = "s3 <b>" + if (p28page.isEmpty()) "s4 x" else "s5 y"

@Language("HTML")
val p28x = mapOf("s6 a" to "s7 b")["s8 k"] ?: ""
