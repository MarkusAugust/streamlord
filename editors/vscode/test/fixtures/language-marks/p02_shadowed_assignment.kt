import org.intellij.lang.annotations.Language

@Language("HTML") var page = "Top <b>x</b>"

fun g() {
    var page = "local"
    page = "R2a <b>x</b>"
}

class B {
    var page = "member"

    fun f() {
        page = "R2b <b>x</b>"
    }
}
