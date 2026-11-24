import org.intellij.lang.annotations.Language

class P20A {
    @Language("HTML")
    var p20page = ""

    init {
        p20page = "I1 <b>x</b>" // missed: #347
    }
}

class P20B {
    val p20b: String
        @Language("HTML")
        get() {
            return "G1 <b>x</b>"
        }
}

@Language("HTML")
fun p20f(): String {
    val s = "R1 <b>x</b>" + p20g()
    return s
}

fun p20g() = ""
