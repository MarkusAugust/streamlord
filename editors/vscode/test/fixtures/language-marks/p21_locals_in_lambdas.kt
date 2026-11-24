import org.intellij.lang.annotations.Language

class P21Obj {
    var p21x = ""
}

fun p21a() {
    @Language("HTML")
    var p21x = ""
    run { p21x = "W1 <b>x</b>" } // missed: #347
    with(P21Obj()) { p21x = "W2 <b>x</b>" } // missed: #347
    listOf(1).forEach { p21x = "W3 <b>x</b>" } // missed: #347
}

@Language("HTML")
var p21top = ""

fun p21b() {
    StringBuilder().apply { p21top = "W4 <b>x</b>" } // missed: #347
}

@Language("HTML")
fun p21c(c: Boolean): String {
    val s = if (c) "R2 <b>x</b>" else "R3 <b>x</b>"
    return s
}

@Language("HTML")
fun p21d(c: Boolean): String {
    val s = "R4 <b>x</b>"
    return if (c) s else ""
}

@Language("HTML")
fun p21e(): String {
    val s = "R5 <b>x</b>"
    return s + "R6 <b>x</b>"
}

class P21F {
    @Language("HTML")
    var p21p = ""

    init {
        this.p21p = "I2 <b>x</b>" // missed: #347
    }

    constructor(a: Int) {
        p21p = "I3 <b>x</b>" // missed: #347
    }
}
