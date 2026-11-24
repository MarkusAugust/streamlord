import org.intellij.lang.annotations.Language

@Language("HTML")
fun p58a(n: Int, c: Boolean): String {
    when (n) {
        1 -> if (c) return "C01 <p>a</p>" else println("C02 x")
        2 -> return if (c) "C03 <p>b</p>" else "C04 <p>c</p>"
        else -> println("C05 y")
    }
    return "C06 <p>d</p>"
}

@Language("HTML")
fun p58b(n: Int, c: Boolean): String {
    val z = when (n) {
        1 -> if (c) return "C07 <p>a</p>" else "C08 b"
        2 -> "C09 c"
        else -> return "C10 <p>e</p>"
    }
    if (c) return z else return "C11 <p>f</p>"
}

@Language("HTML")
fun p58c(n: Int, c: Boolean): String {
    if (c) {
        when (n) {
            1 -> return "C12 <p>a</p>"
            else -> {}
        }
    } else return "C13 <p>b</p>"
    return when {
        c -> "C14 <p>c</p>"
        else -> if (n > 0) "C15 <p>d</p>" else return "C16 <p>e</p>"
    }
}

@Language("HTML")
fun p58d(n: Int, c: Boolean): String {
    var z = "C17 a"
    z = when (n) {
        1 -> {
            if (c) return "C18 <p>a</p>"
            "C19 b"
        }
        else -> "C20 c"
    }
    return z
}

@Language("HTML")
fun p58e(n: Int, c: Boolean): String {
    when {
        c -> return "C21 <p>a</p>"
    }
    if (n > 1) println("C22 x") else return "C23 <p>b</p>"
    return "C24 <p>c</p>"
}
