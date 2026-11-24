import org.intellij.lang.annotations.Language

@Language("HTML")
var p19page = ""

class P19Box<A, B> {
    var p19page = ""
}

fun P19Box<Int, Int>.p19a() {
    p19page = "R1 <b>x</b>"
}

fun (P19Box<Int, Int>).p19b() {
    p19page = "R2 <b>x</b>"
}
