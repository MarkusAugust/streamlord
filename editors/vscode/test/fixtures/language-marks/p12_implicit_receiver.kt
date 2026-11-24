import org.intellij.lang.annotations.Language

@Language("HTML")
var p12head = ""

@Language("HTML")
var p12title = ""

open class P12Doc {
    var p12head = ""
}

class P12Sub : P12Doc() {
    fun f() {
        p12head = "E1 <b>x</b>"
    }
}

fun P12Doc.g() {
    p12head = "E2 <b>x</b>"
}

fun p12h() {
    P12Doc().apply { p12head = "E3 <b>x</b>" }
    with(P12Doc()) { p12head = "E4 <b>x</b>" }
}

class P12Tag {
    var p12title = ""
}

fun p12tag(b: P12Tag.() -> Unit) = P12Tag().b()

fun p12k() {
    p12tag { p12title = "L1 <b>x</b>" }
    p12tag {
        p12title = "L2 <b>x</b>"
    }
}

class P12Page {
    @Language("HTML")
    var p12body = ""

    fun f() {
        p12body = "E6 <b>x</b>" // missed: #347
        this.p12body = "E7 <b>x</b>" // missed: #347
    }
}
