// language=HTML
val x = render("a")

// language=HTML
val y = "Hey <b>x</b>"

// language=HTML
fun f(n: String) = "Ho <b>$n</b>"

// language=HTML
fun g() = "Hi <b>x</b>"

fun k() {
    // language=HTML
    val z = "Local <b>x</b>"
    val w = "after"
}
