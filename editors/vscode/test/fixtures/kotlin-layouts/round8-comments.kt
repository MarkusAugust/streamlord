import org.intellij.lang.annotations.Language
import io.github.markusaugust.streamlord.core.domain.*

// review309/cases2/r_comment_operand
object Case49 {
fun f(fallback: String, n: Int) {
    val name = /* default */ fallback
    val title = "<b>x</b>"
    println("<p>$name</p>")
}
fun g(n: String) {
    fun size(n: Int) = /* c */ n
    println("<p>$n</p>")
}
fun h(n: String, rows: List<Int>) {
    for (n: Int in rows) /* each */ use(n)
    println("<p>$n</p>")
}
fun k(n: String, rows: List<Int>) {
    for (n: Int in rows) /* each */ total += n
    println("<p>$n</p>")
}
}

// review309/cases2/r_comment_quote
object Case50 {
@Language("HTML") // renders the user's "card"
fun card(name: String) = "Hello <b>$name</b>"
@Language("HTML") @JvmName("cardX") // the "x" one
fun cardX(name: String) = "Hello <b>$name</b>"
@Language("HTML") /* "quoted" */ val v = "Hello <b>$name</b>"
}

// review309/cases3/v_comment_operand_getter
object Case61 {
class Card(private val fallback: String) {
    val name = /* default */ fallback
    val html: String
        get() = "<b>x</b>"
}
}

// review309/cases3/v_comment_operand_visible
object Case62 {
fun f(t: Html, s: String) {
    val name = /* kept */ t
    println(s)
    println("<p>$name</p>")
}
fun g(t: Html, s: String) {
    val name = t
    println(s)
    println("<p>$name</p>")
}
}

// review309/cases4/w_comment_then_name_stmt
object Case69 {
fun outer(n: String, rows: List<Int>) {
    for (n: Int in rows) total += /* each */ n
    println("<p>$n</p>")
}
}

