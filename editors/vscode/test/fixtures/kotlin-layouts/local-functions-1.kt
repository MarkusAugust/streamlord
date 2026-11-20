import io.github.markusaugust.streamlord.core.domain.*

// rev309/cases1: c283_basic
object Case44 {
fun outer(n: String) {
    fun size(n: Int) = pad(n)
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_literal
object Case47 {
fun outer(n: String) {
    fun size(n: Int) = "x$n"
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_call_multiline
object Case48 {
fun outer(n: String) {
    fun size(n: Int) = listOf(
        n,
        n,
    )
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_chain
object Case49 {
fun outer(n: String) {
    fun size(n: Int) = listOf(n)
        .map { it + 1 }
        .sum()
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_boolean
object Case53 {
fun outer(n: String) {
    fun ok(n: Int) = n > 0 &&
        n < 10
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_boolean_lead
object Case54 {
fun outer(n: String) {
    fun ok(n: Int) = n > 0
        && n < 10
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_ret_type
object Case56 {
fun outer(n: String) {
    fun size(n: Int): Int = pad(n)
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_object
object Case68 {
fun outer(n: String) {
    fun size(n: Int) = object : Runnable {
        override fun run() { println(n) }
    }
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_expr_lambda_value
object Case70 {
fun outer(n: String) {
    fun size(n: Int) = { n + 1 }
    println("<p>$n</p>")
}
}

// rev309/cases2: r_interpolate_mix
object Case82 {
fun row(name: String, count: Int) = interpolate {
    "<tr><td>${+name}</td><td>${+count}</td></tr>"
}

fun table(rows: List<Pair<String, Int>>) = interpolate {
    val body = rows.map { (n, c) -> row(n, c) }
    "<table>${+body}</table>"
}

fun bad(name: String) = "<p>$name</p>"
fun inner(name: String) {
    fun label(name: Int) = name.toString()
    println("<b>$name</b>")
}
}

// rev309/cases2: r_local_fun_page
object Case92 {
fun render(page: String, rows: List<Row>): String {
    fun cell(page: Int) = pad(page)
    fun row(r: Row) = "<tr>${cell(r.n)}</tr>"
    return "<div>$page${rows.joinToString { row(it) }}</div>"
}
}

// rev309/cases3: o_tabs
object Case114 {
fun outer(n: String) {
	fun size(n: Int) = pad(n)
	println("<p>$n</p>")
}
}

// rev309/cases4: p_expr_word_then_paren_if
object Case139 {
fun outer(n: String) {
    fun f(n: Int) = check(n > 0)
    println("<p>$n</p>")
}
}

// rev309/cases4: p_expr_multiline_string
object Case167 {
fun outer(x: String) {
    fun page(x: Int) = """
        <p>$x</p>
    """
    println("<p>$x</p>")
}
}

// rev309/cases4: p_expr_template_string_multiline
object Case168 {
fun outer(x: String) {
    fun page(x: Int) = "a${
        x
    }b"
    println("<p>$x</p>")
}
}

// rev309/cases4: p_function_types
object Case178 {
fun outer(cb: String) {
    fun reg(cb: (Int) -> Unit) = listeners.add(cb)
    fun reg2(cb: () -> List<Int>) = cb()
    println("<p>$cb</p>")
}
}

// rev309/cases6: s_expr_paren_end_call_newline_dot
object Case194 {
fun outer(n: String) {
    fun f(n: Int) = listOf(n)
        .map { it }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #35
object Case301 {
fun outer(n: String) {
    fun inner(n: Int) = n
        .toString()
        .padStart(3)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #36
object Case302 {
fun outer(n: String) {
    fun inner(n: Int) =
        n.toString()
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #37
object Case303 {
fun outer(n: String) {
    fun inner(n: Int) = listOf(n).map { it ->
        it * 2
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #38
object Case304 {
fun outer(n: String) {
    fun inner(n: Int) = { n + 1 }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #39
object Case305 {
fun outer(n: String) {
    fun inner(n: Int) = "x$n"
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #45
object Case311 {
fun outer(n: String) {
    fun inner(n: Int) = object : Runnable {
        override fun run() = println(n)
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #46
object Case312 {
fun outer(n: String) {
    fun inner(n: Int) = `weird name`(n)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #48
object Case314 {
fun outer(n: String) {
    fun inner(n: Int) = -n
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #49
object Case315 {
fun outer(n: String) {
    fun inner(n: Int) = this::class
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #50
object Case316 {
fun outer(n: String) {
    fun inner(n: Int) = (n + 1)
        .toString()
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #51
object Case317 {
fun outer(n: String) {
    fun inner(n: Int) = (n + 1)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #52
object Case318 {
fun outer(n: String) {
    fun inner(n: Int) = arrayOf(n)[0]
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #54
object Case320 {
fun outer(n: String) {
    fun inner(n: Int) = n is
        Int
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #55
object Case321 {
fun outer(n: String) {
    fun inner(n: Int) = n in
        setOf(1)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #56
object Case322 {
fun outer(n: String) {
    fun inner(n: Int) = n !in
        setOf(1)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #57
object Case323 {
fun outer(n: String) {
    fun inner(n: Int) = this
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #58
object Case324 {
fun outer(n: String) {
    fun inner(n: Int) = basis
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #59
object Case325 {
fun outer(n: String) {
    fun inner(n: Int) = n++
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #60
object Case326 {
fun outer(n: String) {
    fun inner(n: Int) = n -
        1
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #61
object Case327 {
fun outer(n: String) {
    fun inner(n: Int) = n !=
        1
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #62
object Case328 {
fun outer(n: String) {
    fun inner(n: Int) = !
        true
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #68
object Case334 {
fun outer(n: String) {
    fun inner(n: Int) = n > 0 && n < 5 || n>
        9
    println("<p>$n</p>")
}
}

// fresh309-a7f0/a.cases #69
object Case335 {
fun outer(n: String) {
    fun inner(n: Int) = n.let { it ->
        it + 1 }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #6
object Case412 {
fun outer(n: String) {
    fun size(n: Int) = pad(n)
    catchAll("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #7
object Case413 {
fun outer(n: String) {
    fun size(n: Int) = pad(n)
    finallyDo("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #8
object Case414 {
fun outer(n: String) {
    fun size(n: Int) = pad(n)
    elseWhere("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #9
object Case415 {
fun outer(n: String) {
    fun size(n: Int) = pad(n)
    isEmpty("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #13
object Case419 {
fun outer(n: String, a: Int, b: Int, c: Int) {
    fun inner(n: Int) = a<b && c>
        n
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #14
object Case420 {
fun outer(n: String) {
    fun inner(n: Int) = n
    // a comment
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #15
object Case421 {
fun outer(n: String) {
    fun inner(n: Int) = n /* c */
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #16
object Case422 {
fun outer(n: String) {
    fun inner(n: Int) = n // c
        .plus(1)
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #17
object Case423 {
fun outer(n: String) {
    fun inner(n: Int) = "a" +
        n
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #18
object Case424 {
fun outer(n: String) {
    fun inner(n: Int) = """
        <b>$n</b>
    """.trimIndent()
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #19
object Case425 {
fun outer(n: String) {
    fun inner(n: Int) = """
        <b>$n</b>
    """
        .trimIndent()
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #20
object Case426 {
fun outer(n: String) {
    fun inner(n: Int): Int
        = n + 1
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #29
object Case435 {
fun outer(n: String) {
    fun inner(n: Int) = run {
        pad(n)
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #30
object Case436 {
fun outer(n: String) {
    fun inner(n: Int) = run
    {
        pad(n)
    }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #31
object Case437 {
fun outer(n: String) {
    fun inner(n: Int) = listOf(1, 2)
        .map { it + n }
        .filter { it > 0 }
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #32
object Case438 {
fun outer(n: String) {
    fun inner(n: Int) = n.let {
        it + 1
    }.toString()
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #35
object Case441 {
fun outer(n: String) {
    fun inner(n: Int) = n &&
        true
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #36
object Case442 {
fun outer(n: String) {
    fun inner(n: Int) = n
        && true
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #37
object Case443 {
fun outer(n: String) {
    fun inner(n: Int) = n
        || true
    println("<p>$n</p>")
}
}

// fresh309-a7f0/b.cases #38
object Case444 {
fun outer(n: String) {
    fun inner(n: Int) = n::class
        .simpleName
    println("<p>$n</p>")
}
}

