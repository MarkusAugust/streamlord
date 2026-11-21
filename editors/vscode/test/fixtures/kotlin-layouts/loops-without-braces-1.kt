import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.markusaugust.streamlord.core.domain.*
import kotlinx.html.*
import kotlinx.html.stream.createHTML

// rev309/cases1: b296_while_end
object Case36 {
fun f(n: Int) {
    var i = 0
    do { i++ } while (i < n)
    val t = "<b>$i</b>"
}
}

// rev309/cases1: c283_loop_word
object Case45 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) println(n)
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_literal
object Case46 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) "x".also { println(n) }
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_brace_nextline
object Case57 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows)
    {
        println(n)
    }
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_stmt_multiline_call
object Case59 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) println(
        n,
    )
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_stmt_lambda
object Case60 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) run {
        println(n)
    }
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_header_lambda
object Case61 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows.filter { it > 0 }) {
        println("<p>$n</p>")
    }
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_destructure
object Case62 {
fun outer(k: String, m: Map<Int, Int>) {
    for ((k, v) in m) println(k + v)
    println("<p>$k</p>")
}
}

// rev309/cases1: c283_loop_label
object Case63 {
fun outer(n: String, rows: List<Int>) {
    outer@ for (n in rows) if (n > 0) break@outer
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_stmt_if_else_nextline
object Case64 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows)
        if (n > 0) println(n)
        else println(-n)
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_stmt_assignment
object Case65 {
fun outer(n: String, rows: List<Int>) {
    var total = 0
    for (n in rows) total += n
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_stmt_continue_op
object Case66 {
fun outer(n: String, rows: List<Int>) {
    var total = 0
    for (n in rows) total +=
        n * 2
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_nested_loops
object Case71 {
fun outer(n: String, rows: List<List<Int>>) {
    for (row in rows)
        for (n in row) println(n)
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_while
object Case72 {
fun outer(n: String) {
    var i = 0
    while (i < 3) i++
    println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_body_word_else_of_outer
object Case77 {
fun outer(n: String, a: Boolean, rows: List<Int>) {
    if (a)
        for (n in rows) println(n)
    else
        println("<p>$n</p>")
}
}

// rev309/cases1: c283_loop_body_if_ifelse
object Case78 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) println(n) else if (n < 0) println(-n)
    else println(0)
    println("<p>$n</p>")
}
}

// rev309/cases2: r_ktor_routes
object Case80 {
fun Application.routes(repo: Repo) {
    routing {
        get("/users/{id}") {
            val id = call.parameters["id"]!!
            val user = repo.find(id) as User?
            val names = repo.all() as List<String>
            for (n in names) log(n)
            call.respondText("<p>${user?.name} $id</p>", ContentType.Text.Html)
        }
        get("/list") {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val items = if (page > 0)
                repo.page(page)
            else
                repo.first()
            call.respondText("<ul>${items.joinToString("") { "<li>$it</li>" }}</ul>")
        }
    }
}
}

// rev309/cases2: r_kotlinx_html
object Case81 {
fun FlowContent.card(title: String, body: String) = div("card") {
    h2 { +title }
    p { +body }
}

fun page(title: String, items: List<String>): String = createHTML().html {
    body {
        h1 { +title }
        ul {
            for (item in items) li { +item }
        }
        for (item in items)
            p { +item }
    }
}
fun other(item: String) = "<p>$item</p>"
}

// rev309/cases2: r_loops_multiline
object Case87 {
fun list(items: List<String>, title: String): String {
    val sb = StringBuilder()
    for (item in items
        .filter { it.isNotBlank() }
        .sorted()) {
        sb.append("<li>$item</li>")
    }
    for (i in 0 until
        items.size) sb.append(i)
    for ((index, item) in items.withIndex())
        sb.append("<li>$index $item</li>")
    var k = 0
    while (k < items.size)
        k++
    return "<h1>$title</h1><ul>$sb</ul>$k"
}
}

// rev309/cases3: o_unicode_names
object Case110 {
fun f(æøå: String, rows: List<Int>) {
    for (æøå in rows) println(æøå)
    println("<p>$æøå</p>")
}
}

// rev309/cases3: o_deep_nesting
object Case123 {
fun f(n: String) {
    val a = listOf(listOf(listOf(listOf(listOf(listOf(listOf(listOf(1))))))))
    for (n in a) for (m in n) for (k in m) println(k)
    println("<p>$n</p>")
}
}

// rev309/cases3: o_if_no_else_stmt_semicolon
object Case127 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) println(n); println("<p>$n</p>")
}
}

// rev309/cases4: p_loop_stmt_infix
object Case138 {
fun outer(n: String, rows: List<Int>, out: MutableMap<Int, String>) {
    for (n in rows) out += n to
        "<p>$n</p>"
}
}

// rev309/cases4: p_while_loop_stmt_value
object Case142 {
fun outer(n: String) {
    var i = 0
    val a = run {
        while (i < 3)
            i++
        i
    }
    val b = "<p>$a</p>"
}
}

// rev309/cases4: p_do_while_then_while
object Case143 {
fun outer(n: String) {
    var i = 0
    do {
        i++
    }
    while (i < 3)
    val b = "<p>$n</p>"
}
}

// rev309/cases4: p_loop_stmt_when
object Case148 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) when (n) {
        1 -> println(n)
        else -> println(-n)
    }
    println("<p>$n</p>")
}
}

// rev309/cases4: p_loop_stmt_if_block_else_nextline
object Case149 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0) {
        println(n)
    }
    else {
        println(-n)
    }
    println("<p>$n</p>")
}
}

// rev309/cases4: p_loop_stmt_chain_next_line
object Case151 {
fun outer(n: String, rows: List<Int>, list: MutableList<Int>) {
    for (n in rows) list
        .add(n)
    println("<p>$n</p>")
}
}

// rev309/cases4: p_loop_header_multiline_typed
object Case152 {
fun outer(item: String, items: List<Int>) {
    for (item: Int
         in items) println(item)
    println("<p>$item</p>")
}
}

// rev309/cases4: p_loop_header_when_inside
object Case153 {
fun outer(item: String, items: List<Int>, a: Int) {
    for (item in when (a) { 1 -> items; else -> emptyList() }) println(item)
    println("<p>$item</p>")
}
}

// rev309/cases4: p_loop_header_if_inside
object Case154 {
fun outer(item: String, items: List<Int>, a: Boolean) {
    for (item in if (a) items else emptyList()) println(item)
    println("<p>$item</p>")
}
}

// rev309/cases4: p_loop_header_lambda_with_param
object Case155 {
fun outer(item: String, items: List<Int>) {
    for (item in items.map { x -> x + 1 }) println(item)
    println("<p>$item</p>")
}
}

// rev309/cases4: p_loop_body_literal_template
object Case157 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) "<i>$n</i>".also(::println)
    println("<p>$n</p>")
}
}

// rev309/cases4: p_loop_stmt_paren
object Case158 {
fun outer(n: String, rows: List<Any>) {
    for (n in rows) (n as Runnable).run()
    println("<p>$n</p>")
}
}

// rev309/cases4: p_loop_in_lambda_in_expr
object Case160 {
fun outer(n: String, rows: List<Int>) = buildString {
    for (n in rows) append(n)
    append("<p>$n</p>")
}
}

// rev309/cases4: p_loop_stmt_return
object Case161 {
fun outer(n: String, rows: List<Int>): String {
    for (n in rows) return "<b>$n</b>"
    return "<p>$n</p>"
}
}

// rev309/cases4: p_loop_stmt_if_return_else_outer
object Case162 {
fun outer(n: String, a: Boolean, rows: List<Int>): String {
    if (a) for (n in rows) if (n > 0) return "x"
    else return "<p>$n</p>"
    return ""
}
}

// rev309/cases5: q_stmt_then_literal_call
object Case179 {
fun f(n: String, rows: List<Int>) = run {
    var total = 0
    for (n: Int in rows) total += n
    "<p>$n</p>".also(::println)
}
}

// rev309/cases5: q_stmt_then_raw_trim
object Case180 {
fun f(n: String, rows: List<Int>) = run {
    var total = 0
    for (n: Int in rows) total += n
    """<p>$n</p>""".trimIndent()
}
}

// rev309/cases5: q_stmt_word_then_literal_call
object Case181 {
fun f(n: String, rows: List<Int>) = run {
    for (n: Int in rows) println(n)
    "<p>$n</p>".also(::println)
}
}

// rev309/cases5: q_stmt_then_literal_braces
object Case182 {
fun f(n: String, rows: List<Int>) = run {
    var total = 0
    for (n: Int in rows) total += n
    "<p>${n}</p>".also(::println)
}
}

// rev309/cases5: q_stmt_then_literal_plain
object Case184 {
fun f(n: String, rows: List<Int>) = run {
    var total = 0
    for (n: Int in rows) total += n
    "<p>$n</p>"
}
}

// rev309/cases5: q_stmt_ends_bang_then_literal
object Case186 {
fun f(n: String, rows: List<Int?>) = run {
    for (n: Int? in rows) use(n!!)
    "<p>$n</p>".also(::println)
}
}

// rev309/cases6: s_interpolate_for_then_literal
object Case189 {
fun list(n: String, rows: List<Int>) = interpolate {
    for (n: Int in rows) println(n)
    "<p>${+n}</p>".trim()
}
}

// rev309/cases6: s_buildstring_for_trim
object Case190 {
fun page(title: String, rows: List<Int>) = buildString {
    for (title: Int in rows) append(title)
    append("""
        <h1>$title</h1>
    """.trimIndent())
}
}

// rev309/cases6: s_for_header_paren_then_stmt_paren
object Case195 {
fun outer(n: String, rows: List<Int>) {
    for (n in rows) if (n > 0)
        println(n)
    println("<p>$n</p>")
}
}

// r5review/cases: d34_ifs_elvis
object Case252 {
fun pick(x: String, a: Boolean, xs: List<Int>) {
    if (a)
        for (x: Int in xs) total += if (x > 0) x else 0
    else
        println("<b>$x</b>")
}
}

// r6review/cases: c04_catchup_call.kt
object Case256 {
fun catchUp(a: String) {}
fun page(n: String, items: List<Int>): String {
    var out = ""
    for (n in items) out += n
    catchUp("<p>$n</p>")
    return out
}
}

// r6review/cases: c10_else.kt
object Case262 {
fun page(x: Int, xs: List<String>, ok: Boolean): String {
    var out = ""
    if (ok)
        for (x in xs) if (x.isEmpty()) out += x else out += "-"
    else
        out += "<p>1 $x</p>"
    if (ok)
        for (x in xs) if (x.isEmpty()) out += x
        else out += "<p>2 $x</p>"
    if (ok)
        for (x in xs) xs.forEach { if (it.isEmpty()) out += it }
    else
        out += "<p>3 $x</p>"
    if (ok)
        for (x in xs) out += when (x) { "a" -> if (ok) 1 else 2; else -> 3 }
    else
        out += "<p>4 $x</p>"
    if (ok)
        for (x in xs) notify(x)
    else
        out += "<p>5 $x</p>"
    if (ok)
        for (x in xs) ifs(x)
    else
        out += "<p>6 $x</p>"
    if (ok)
        for (x in xs) if (ok) if (ok) out += x else out += "a"
        else out += "<p>7 $x</p>"
    if (ok)
        for (x in xs) f(if (ok) 1 else 2)
    else
        out += "<p>8 $x</p>"
    if (ok)
        for (x in xs) if (ok) f(if (ok) 1 else 2)
        else out += "<p>9 $x</p>"
    if (ok)
        for (x in if (ok) xs else xs) f(1)
    else
        out += "<p>10 $x</p>"
    if (ok)
        for (x in xs) if (ok) out += x else if (ok) out += x
        else out += "<p>11 $x</p>"
    if (ok)
        for (x in xs) if (ok) out += x else if (ok) out += x else out += x
    else
        out += "<p>12 $x</p>"
    if (ok)
        for (x in xs) if (ok) out += "${if (ok) 1 else 2}"
        else out += "<p>13 $x</p>"
    if (ok)
        for (x in xs) if (ok) out += "${if (ok) 1 else 2}" else out += x
    else
        out += "<p>14 $x</p>"
    if (ok)
        for (x in xs) if (ok) xs.map { if (ok) 1 else 2 }
        else out += "<p>15 $x</p>"
    if (ok)
        for (x in xs) if (ok) xs.map { if (ok) 1 else 2 } else out += x
    else
        out += "<p>16 $x</p>"
    return out
}
fun notify(s: String) {}
fun ifs(s: String) {}
fun f(n: Int) {}
}

// r6review/cases: c12_misc.kt
object Case264 {
fun page(n: String, xs: List<Int>): String {
    var out = ""
    for (n in xs) out += n
    finallyDone("<p>$n</p>")
    for (n in xs) out += n
    catching("<p>$n</p>")
    return out
}
fun finallyDone(s: String) {}
fun catching(s: String) {}
}

// r6review/cases2: d03_catch_names.kt
object Case268 {
fun catchUp(a: String) {}
fun page(n: String, items: List<Int>): String {
    var out = ""
    for (n: Int in items) out += n
    catchUp("<p>$n</p>")
    val c = items.size
    catchCount++
    return out
}
var catchCount = 0
}

// fresh309-a7f0/a.cases #5
object Case271 {
fun page(name: String, items: List<String>): String {
    var count = 0
    for (item in items) count++
    val label = name as String?
    val list = items as List<String>
    val first = items.firstOrNull()!!
    return "<p>$name $count $label $list $first</p>"
}
}

// fresh309-a7f0/a.cases #10
object Case276 {
fun Application.module() {
    routing {
        get("/hi/{name}") {
            val name = call.parameters["name"]!!
            val count = call.request.queryParameters["n"]?.toInt()
                ?: 0
            for (i in 0 until count)
                println(i)
            call.respondText("<p>Hi $name $count</p>", ContentType.Text.Html)
        }
    }
}
}

// fresh309-a7f0/a.cases #11
object Case277 {
fun render(items: List<String>, title: String) = createHTML().div {
    h1 { +title }
    for (item in items) p { +item }
    ul {
        for (item in items)
            li { +item }
    }
}
fun after(item: Int) = "<p>$item</p>"
}

// fresh309-a7f0/a.cases #16
object Case282 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs)
        if (x > 0)
            println(x)
        else
            println(-x)
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #17
object Case283 {
fun f(xs: List<Int>, x: String, a: Boolean) {
    if (a)
        for (x: Int in xs) if (x > 0) println(x) else println(-x)
    else
        println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #18
object Case284 {
fun f(xs: List<Int>, x: String, a: Boolean) {
    if (a)
        for (x: Int in xs) if (x > 0) println(x)
    else
        println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #19
object Case285 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) when (x) {
        1 -> println("one")
        else -> println("other")
    }
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #22
object Case288 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) list.add(
        x
    )
    println("<p>$x</p>")
}
}

// fresh309-a7f0/a.cases #23
object Case289 {
fun f(xs: List<Int>, x: String) {
    for (x: Int in xs) // comment here
        println(x)
    println("<p>$x</p>")
}
}

