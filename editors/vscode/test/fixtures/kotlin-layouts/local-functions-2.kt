// fresh309-a7f0/b.cases #94
object Case500 {
fun outer(n: String) {
    fun inner(n: Int) = do2 {
        n
    } while2 (n > 0)
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-literal
object Case570 {
fun outer(n: String) {
    fun size(n: Int) = "x".length + n
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-paren
object Case571 {
fun outer(n: String) {
    fun size(n: Int) = (n + 1)
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-next-line
object Case572 {
fun outer(n: String) {
    fun size(n: Int) =
        pad(n)
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-plus-next-line
object Case580 {
fun outer(n: String) {
    fun size(n: Int) = n +
        1
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-and-next-line
object Case581 {
fun outer(n: String) {
    fun size(n: Int) = n > 0
        && n < 10
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-lambda-arg
object Case582 {
fun outer(n: String) {
    fun size(n: Int) = run {
        n + 1
    }
    println("<p>$n</p>")
}
}

// review-309/lines.kt: fun-expr-trailing-lambda-next-line
object Case583 {
fun outer(n: String) {
    fun size(n: Int) = listOf(n).map
    { it + 1 }
    println("<p>$n</p>")
}
}

// review-309/loops.kt: fun-expr-string-multiline
object Case614 {
fun outer(n: String) {
    fun size(n: Int) = """
        x $n
    """.length
    println("<p>$n</p>")
}
}

// review-309/loops.kt: fun-expr-object
object Case615 {
fun outer(n: String) {
    fun size(n: Int) = object : Runnable {
        override fun run() = println(n)
    }
    println("<p>$n</p>")
}
}

// review-309/loops.kt: fun-expr-minus
object Case618 {
fun outer(n: String) {
    fun neg(n: Int) = -n
    println("<p>$n</p>")
}
}

// review-309/loops.kt: fun-expr-not
object Case619 {
fun outer(n: String) {
    fun neg(n: Boolean) = !n
    println("<p>$n</p>")
}
}

// review-309/loops.kt: fun-expr-lambda
object Case620 {
fun outer(n: String) {
    fun neg(n: Int) = { n + 1 }
    println("<p>$n</p>")
}
}

// review-309/loops.kt: fun-expr-ref
object Case621 {
fun outer(n: String) {
    fun neg(n: Int) = ::println
    println("<p>$n</p>")
}
}

// review-309/loops.kt: fun-expr-typed-next-line
object Case622 {
fun outer(n: String) {
    fun size(n: Int): Int =
        n + 1
    println("<p>$n</p>")
}
}

// review-309/unicode.kt: unicode-local
object Case699 {
fun ytre(n: String) {
    fun størrelse(n: Int) = påfyll(n)
    println("<p>$n</p>")
}
}

// r5review/cases: d26_expr_paren
object Case726 {
fun outer(n: String) {
    fun one(n: Int) = (n + 1)
    println("<p>$n</p>")
    fun two(n: Int) = listOf(n)[0]
    println("<i>$n</i>")
    fun three(n: Int) = { n }
    println("<b>$n</b>")
}
}

// r7/probes7: k02-local-get
object Case916 {
fun outer() {
    val title = load(); get() = "<i>"
    println("<p>$title</p>")
}
}

