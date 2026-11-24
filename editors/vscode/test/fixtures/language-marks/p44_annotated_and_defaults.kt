import org.intellij.lang.annotations.Language

fun p44f() {
    // language=HTML
    val a = @Suppress("x") "A1 <b>x</b>" // missed: #347
    val b = "A2 plain"
    println(a + b)
}

fun p44g(s: String) = s

fun p44h(@Language("HTML") a: String = p44g("A3 x"), b: String = "A4 y") = a + b
