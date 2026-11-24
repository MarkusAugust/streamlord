import org.intellij.lang.annotations.Language

@Language("HTML")
var p25page = ""

interface P25Has {
    var p25page: String
}

open class P25Base {
    open var p25page = ""
}

fun p25a() {
    val o =
        object : P25Base() {
            fun f() {
                p25page = "N1 x"
            }
        }
    val p =
        object {
            fun f() {
                p25page = "N2 <b>x</b>" // missed: #347
            }
        }
}

class P25Impl : P25Has {
    override var p25page = ""

    fun f() {
        p25page = "N3 x"
    }
}

fun p25b(xs: List<String>) {
    p25page = xs.joinToString("N4 , ") { "N5 <li>$it</li>" } // missed: #347
    p25page = "N6 <b>".replace("N7 b", "N8 i") // missed: #347
    p25page = String.format("N9 <b>%s</b>", 1) // missed: #347
    p25page = listOf("O1 a", "O2 b").first() // missed: #347
    p25page = buildString { append("O3 <b>") }
    p25page = "O4 <b>" + xs.joinToString { "O5 x" } // missed: #347
    p25page = (if (xs.isEmpty()) "O6 <b>" else "O7 <i>") + "O8 </i>" // missed: #347
    p25page = p25wrap("O9 <b>x</b>") // missed: #347
    p25page = "P1 <b>".let { it + "P2 x" } // missed: #347
    p25page = xs.firstOrNull() ?: p25wrap("P3 x")
    p25page = p25wrap("P4 x") ?: "P5 x"
    p25page = """
        P6 <b>x</b>
    """.trimIndent() // missed: #347
    p25page = "P7 <b>$p25page ${"P8 inner"}</b>" // missed: #347
    p25page = mapOf("P9 k" to "Q1 v")["Q2 k"] ?: ""
    p25page = run { "Q3 <b>" }
    p25page = xs.map { "Q4 <b>" }.first()
    p25page = p25wrap(p25wrap("Q5 <b>")) + "Q6 x" // missed: #347
    p25page = p25wrap("Q7 a") + p25wrap("Q8 b")
}

fun p25wrap(s: String): String = s

fun p25c(p25page: P25Has) {
    p25page.p25page = "Q9 x"
}

fun p25d() {
    var p25page = ""
    p25page = "R1 x"
    run {
        p25page = "R2 x"
    }
}

fun p25e(xs: List<String>) {
    for (p25page in xs) {
        println(p25page)
    }
    p25page = "R3 <b>x</b>" // missed: #347
}

fun p25f(m: Map<String, String>) {
    val p25 = mutableMapOf<String, String>()
    p25["k"] = "R4 x"
    p25page = p25["k"] ?: "R5 x"
}

class P25G {
    var p25page = ""

    companion object {
        fun f() {
            p25page = "R6 <b>x</b>" // missed: #347
        }
    }
}

class P25H {
    var p25page = ""

    inner class I {
        fun f() {
            p25page = "R7 x"
        }
    }

    class N {
        fun f() {
            p25page = "R8 <b>x</b>" // missed: #347
        }
    }
}

fun p25i(b: P25Base) {
    b.apply {
        p25page = "R9 x"
    }
    b.also {
        p25page = "S1 <b>x</b>" // missed: #347
    }
    b.run { p25page = "S2 x" }
    b.let { p25page = "S3 <b>x</b>" } // missed: #347
}

fun p25j() {
    if (true) p25page = "S4 <b>x</b>" // missed: #347
    else p25page = "S5 <b>x</b>" // missed: #347
    p25page = if (true) "S6 <b>x</b>" else p25wrap("S7 <b>x</b>") // missed: #347
    p25page = when { true -> "S8 <b>x</b>"; else -> "S9 <b>x</b>" } // missed: #347
    p25page = try { "T1 <b>x</b>" } catch (e: Exception) { "T2 <b>x</b>" }
    p25page = "T3 <b>" + if (true) "T4 x" else "T5 y" // missed: #347
    p25page = "T6 <b>" + (if (true) "T7 x" else "T8 y") // missed: #347
    p25page = "T9 <b>" + p25wrap("U1 x") // missed: #347
    p25page = p25wrap("U2 x") + "U3 <b>" // missed: #347
}
