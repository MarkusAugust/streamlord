// review-309/lines.kt: do-while-nobrace
object Case557 {
fun f(n: Int) {
    var k = n
    do k-- while (k > 0)
    val t = "<b>$k</b>"
}
}

// review-309/lines.kt: while-nobrace-next
object Case558 {
fun f(n: Int) {
    var k = n
    while (k > 0)
        k--
    val t = "<b>$k</b>"
}
}

// review-309/lines.kt: for-header-multi
object Case559 {
fun f(title: Int, items: List<String>) {
    for ((i, title) in
        items.withIndex())
        println(title)
    println("<p>$title</p>")
}
}

// review-309/lines.kt: for-body-word
object Case560 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) println(n)
    println("<p>$n</p>")
}
}

// review-309/lines.kt: for-body-literal
object Case561 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) "x$n".length
    println("<p>$n</p>")
}
}

// review-309/lines.kt: for-body-call-multiline
object Case562 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) println(
        n,
    )
    println("<p>$n</p>")
}
}

// review-309/lines.kt: for-body-chain
object Case563 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) list
        .add(n)
    println("<p>$n</p>")
}
}

// review-309/lines.kt: for-body-elvis
object Case564 {
fun f(n: String, rows: List<Int?>) {
    for (n in rows) total += n
        ?: 0
    println("<p>$n</p>")
}
}

// review-309/lines.kt: for-brace
object Case565 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) {
        println(n)
    }
    println("<p>$n</p>")
}
}

// review-309/lines.kt: for-brace-next-line
object Case566 {
fun f(n: String, rows: List<Int>) {
    for (n in rows)
    {
        println(n)
    }
    println("<p>$n</p>")
}
}

// review-309/lines.kt: for-lambda-header
object Case567 {
fun f(n: String, rows: List<Int>) {
    for (n in rows.filter { it > 0 }) println(n)
    println("<p>$n</p>")
}
}

// review-309/lines.kt: for-nested-nobrace
object Case568 {
fun f(n: String, rows: List<List<Int>>) {
    for (r in rows) for (n in r) println(n)
    println("<p>$n</p>")
}
}

// review-309/lines.kt: while-body-word
object Case569 {
fun f(n: String) {
    var k = 3
    while (k > 0) k--
    println("<p>$n</p>")
}
}

// review-309/lines.kt: while-then-brace-line
object Case603 {
fun f(a: Int) {
    var k = a
    while (k > 0)
    {
        k--
    }
    val t = "<b>$k</b>"
}
}

// review-309/loops.kt: for-when-block
object Case604 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) when (n) {
        1 -> println(n)
        else -> println(n + 1)
    }
    println("<p>$n</p>")
}
}

// review-309/loops.kt: for-if-block
object Case605 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) {
        println(n)
    }
    println("<p>$n</p>")
}
}

// review-309/loops.kt: for-if-block-else
object Case606 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) {
        println(n)
    } else {
        println(-n)
    }
    println("<p>$n</p>")
}
}

// review-309/loops.kt: for-lambda-block
object Case607 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) rows.forEach {
        println(n + it)
    }
    println("<p>$n</p>")
}
}

// review-309/loops.kt: for-sync-block
object Case608 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) synchronized(this) {
        println(n)
    }
    println("<p>$n</p>")
}
}

// review-309/loops.kt: for-label
object Case611 {
fun f(n: String, rows: List<Int>) {
    outer@ for (n in rows) println(n)
    println("<p>$n</p>")
}
}

// review-309/loops.kt: for-string-body
object Case613 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) println("""
        x $n
    """)
    println("<p>$n</p>")
}
}

// r5review/cases: d01_ifs_matched
object Case702 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) if (x > 0) use(x) else skip()
    else
        println("<b>$x</b>")
}
}

// r5review/cases: d02_ifs_lambda
object Case703 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) run { if (x > 0) use(x) }
    else
        println("<b>$x</b>")
}
}

// r5review/cases: d03_ifs_template
object Case704 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) println("${if (x > 0) 1 else 2}")
    else
        println("<b>$x</b>")
}
}

// r5review/cases: d04_elseif_chain
object Case705 {
fun pick(x: String, xs: List<Int>) {
    for (x: Int in xs) if (x > 0) use(x)
    else if (x < 0) use(-x)
    else println("<b>$x</b>")
    println("<i>$x</i>")
}
}

// r5review/cases: d05_dangling
object Case706 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) if (x > 0) use(x)
    else
        println("<b>$x</b>")
    println("<i>$x</i>")
}
}

// r5review/cases: d06_ifs_paren
object Case707 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) use(if (x > 0) 1 else 2)
    else
        println("<b>$x</b>")
}
}

// r5review/cases: d07_elvis_if
object Case708 {
fun pick(x: String, a: Boolean, xs: List<Int?>) {
    if (a)
        for (x: Int? in xs) use(x ?: return) else println("<b>$x</b>")
    println("<i>$x</i>")
}
}

// r5review/cases: d08_header_lambda
object Case709 {
fun list(item: String, items: List<Int>) {
    for (item: Int in items.filter { it > 0 }) println(item)
    println("<li>$item</li>")
    for ((k, v) in mapOf(1 to 2).entries.sortedBy { it.key })
        println(k + v)
    println("<p>$item</p>")
    for (item: Int in when (item.length) { 0 -> items else -> emptyList() }) use(item)
    println("<b>$item</b>")
}
}

// r5review/cases: d09_header_object
object Case710 {
fun list(item: String, items: List<Int>) {
    for (item: Int in items.sortedWith(object : Comparator<Int> { override fun compare(a: Int, b: Int) = a - b }))
        use(item)
    println("<li>$item</li>")
}
}

// r5review/cases: d10_header_fun
object Case711 {
fun list(item: String, items: List<Int>) {
    for (item: Int in items.map(fun(y: Int): Int = y)) {
        println("<li>$item</li>")
    }
    println("<p>$item</p>")
}
}

// r5review/cases: d11_brace_body
object Case712 {
fun list(item: String, items: List<Int>) {
    for (item: Int in items)
    {
        println("<li>$item</li>")
    }
    println("<p>$item</p>")
    for (item: Int in items) // comment
        println("<b>$item</b>")
    println("<i>$item</i>")
}
}

// r5review/cases: d15_dowhile
object Case716 {
fun f(n: String, xs: List<Int>) {
    for (n: Int in xs) do { tick(n) } while (n > 0)
    println("<p>$n</p>")
    var i = 0
    do i++ while (i < 3)
    val v = i
    println("<b>$v</b>")
}
}

// r5review/cases: d16_whilecond
object Case717 {
fun f(n: String, xs: List<Int>) {
    if (xs.isEmpty()) { stop() }
    while (busy())
        wait()
    for (n: Int in xs) while (n > 0)
        tick(n)
    println("<p>$n</p>")
}
}

// r5review/cases: d20_ifs_chain_closed
object Case721 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) if (x > 0) use(x) else if (x < 0) skip() else stop()
    else
        println("<b>$x</b>")
}
}

// r5review/cases: d22_header_lines
object Case722 {
fun pick(x: String, xs: List<Int>) {
    for (x: Int in xs
        .map { it + 1 }) println(x)
    println("<b>$x</b>")
}
}

// r5review/cases: d23_stmt_literal
object Case723 {
fun pick(x: String, xs: List<Int>) {
    for (x: Int in xs) "<p>$x</p>".also(::println)
    println("<b>$x</b>")
}
}

// r5review/cases: d24_stmt_when
object Case724 {
fun pick(x: String, xs: List<Int>) {
    for (x: Int in xs) when (x) {
        1 -> println("<p>$x</p>")
    }
    println("<b>$x</b>")
}
}

// r5review/cases: d25_lambda_loop
object Case725 {
fun pick(x: String, xs: List<Int>) {
    val r = buildString { for (x: Int in xs) append(x) }
    println("<b>$x</b>")
}
}

// r5review/cases: d29_if_at_end
object Case727 {
fun pick(x: String, xs: List<Int>) {
    for (x: Int in xs) if (x > 0)
        use(x)
    else skip(x)
    println("<b>$x</b>")
}
}

// r5review/cases: d31_ifs_word
object Case729 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) use(x, `if` = 1)
    else
        println("<b>$x</b>")
}
}

// r5review/cases: d32_if_return
object Case730 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) if (x > 0) return
    else
        println("<b>$x</b>")
    println("<i>$x</i>")
}
}

