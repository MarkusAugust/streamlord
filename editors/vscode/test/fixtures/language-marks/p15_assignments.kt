import org.intellij.lang.annotations.Language

@Language("HTML")
var p15page = ""

fun p15a(xs: List<Int>) {
    xs.forEach { p15page = "E1 <b>x</b>" } // missed: #347
    if (xs.isEmpty()) { p15page = "E2 <b>x</b>" } // missed: #347
    for (x in xs) { p15page = "E3 <b>x</b>" } // missed: #347
    while (xs.isEmpty()) p15page = "E4 <b>x</b>" // missed: #347
    try { p15page = "E5 <b>x</b>" } finally { p15page = "E6 <b>x</b>" } // missed: #347
    p15page = if (xs.isEmpty()) "E7 <b>x</b>" else "E8 <b>x</b>" // missed: #347
    p15page = when (xs.size) { 1 -> "E9 <b>x</b>" else -> "F1 <b>x</b>" } // missed: #347
    p15page = p15page + "F2 <b>x</b>" // missed: #347
    p15page = xs.firstOrNull()?.toString() ?: "F3 <b>x</b>"
    p15page = if (xs.isEmpty()) {
        "F4 <b>x</b>"
    } else "F5 <b>x</b>" // missed: #347
    when (xs.size) {
        1 -> { p15page = "F6 <b>x</b>" } // missed: #347
    }
    xs.let { p15page = "F7 <b>x</b>" } // missed: #347
}

class P15A {
    fun f() {
        p15page = "F8 <b>x</b>" // missed: #347
    }
}

fun p15b() {
    @Language("HTML")
    var s = ""
    s = "F9 <b>x</b>" // missed: #347
    if (s.isEmpty()) {
        s = "G1 <b>x</b>" // missed: #347
    }
    run { s = "G2 <b>x</b>" } // missed: #347
    println(s)
}

class P15C {
    @Language("HTML")
    var body = ""

    inner class In {
        fun f() {
            body = "G3 <b>x</b>" // missed: #347
        }
    }

    fun g() {
        this@P15C.body = "G4 <b>x</b>" // missed: #347
        run { body = "G5 <b>x</b>" } // missed: #347
    }
}

fun Map<String, Int>.p15d() {
    p15page = "G6 <b>x</b>" // missed: #347
}

fun List<Int>.p15e() {
    p15page = "G7 <b>x</b>" // missed: #347
}

fun p15f(c: Boolean) {
    if (c) p15page = "G8 <b>x</b>" else p15page = "G9 <b>x</b>" // missed: #347
    p15page += "H1 <b>x</b>" // missed: #347
    p15page =
        "H2 <b>x</b>" // missed: #347
    p15page = "H3 <b>" + // missed: #347
        "H4 </b>" // missed: #347
    val other = "H5 <b>x</b>"; p15page = "H6 <b>x</b>" // missed: #347
}
