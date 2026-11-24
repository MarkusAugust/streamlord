import org.intellij.lang.annotations.Language

@Language("HTML")
var p16page = ""

fun p16a(xs: List<Int>) {
    xs.forEach {
        when (it) {
            1 -> p16page = "J1 <b>x</b>" // missed: #347
        }
    }
    when (xs.size) {
        1 -> p16page = "J2 <b>x</b>" // missed: #347
        2 -> println("J3 <b>x</b>")
        else -> {
            p16page = "J4 <b>x</b>" // missed: #347
        }
    }
    when (xs.size) {
        1 -> p16page =
            "J5 <b>x</b>" // missed: #347
        2 -> "J6 <b>x</b>"
    }
}

@Language("HTML")
fun p16b(c: Int): String {
    when (c) {
        1 -> return "J7 <b>x</b>"
        else -> return if (c > 2) "J8 <b>x</b>" else "J9 <b>x</b>"
    }
}

fun p16c(c: Int) {
    // language=HTML
    val s = when (c) { 1 -> "K1 <b>x</b>" else -> "K2 <b>x</b>" }
    println(s)
}
