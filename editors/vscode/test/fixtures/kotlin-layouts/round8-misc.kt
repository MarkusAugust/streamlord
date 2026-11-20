// review309/cases2/r_function_named_if
object Case51 {
fun f(n: String) {
    val x = check(n.isNotEmpty())
    val title = "<b>$x</b>"
    val y = require(n.length > 1)
    val t2 = "<b>$y</b>"
    val z = notif(n)
    val t3 = "<b>$z</b>"
    val w = elif (n)
    val t4 = "<b>$w</b>"
}
}

// review309/cases2/r_two_words
object Case55 {
class A {
    val x = 1
    private companion object
    val y = 2
}
fun f(name: String) {
    val a = name
    lateinit var b: String
    val c = name.let { it }
    println("<p>$a $c</p>")
}
}

