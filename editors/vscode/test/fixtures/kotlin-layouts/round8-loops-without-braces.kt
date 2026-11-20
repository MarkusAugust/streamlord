import io.github.markusaugust.streamlord.core.domain.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.html.*
import kotlinx.html.*

// review309/cases/c25
object Case17 {
fun f(n: String) {
    for (n: Int in xs) use(n)
    // "x"
    "<p>$n</p>".let(::println)
}
}

// review309/cases/c40
object Case30 {
fun f(n: String) {
    for (n: Int in xs) if (a) if (b) c else d else e
    println("<p>$n</p>")
}
}

// review309/cases/interpolate
object Case40 {
fun card(title: String, body: Html, count: Int) = interpolate {
    "<div class='card'><h2>${+title}</h2>${+body}<span>${+count}</span></div>"
}

fun list(items: List<String>): Html = interpolate {
    val rows = items.map { interpolate { "<li>${+it}</li>" } }
    "<ul>${+rows}</ul>"
}

fun page(name: String) = interpolate("<p>${name}</p>")

fun nested(user: String?) {
    val greeting = user!!
    val html = interpolate { "<b>${+greeting}</b>" }
    val raw = "<b>$greeting</b>"
}

fun trusted() = Trusted("<b>fixed</b>")
fun block(n: String) = interpolate {
    for (n: Int in listOf(1)) println(n)
    "<p>${+n}</p>"
}
}

// review309/cases/ktor
object Case41 {
fun Application.module() {
    routing {
        get("/") {
            val name = call.parameters["name"]!!
            val n = call.parameters["n"]?.toIntOrNull() ?: 0
            call.respondText("<p>Hello $name, $n</p>", ContentType.Text.Html)
        }
        get("/list") {
            val rows = (1..10).toList()
            for (r in rows) call.application.log.info("row $r")
            val page = interpolate { "<ul>${+rows.joinToString()}</ul>" }
            call.respondText(page.text, ContentType.Text.Html)
        }
        get("/html") {
            call.respondHtml {
                body {
                    div {
                        attributes["data-signals"] = "{count: 0}"
                        +"Hi"
                    }
                }
            }
        }
        post("/sse") {
            call.respondSse {
                patchElements("<div id='x'>${call.parameters["v"]}</div>")
            }
        }
    }
}

fun Route.users(service: UserService) = route("/users") {
    get { call.respond(service.all()) }
}
fun Route.items(name: String) {
    fun row(name: Int) = name.toString()
    get("/i") { call.respondText("<p>$name</p>") }
}
}

// review309/cases/weird
object Case47 {
fun f(n: String, xs: List<Int>) {
    do println(n) while (xs.isEmpty())
    val a = n
    println("<p>$a</p>")
    if (xs.isEmpty())
        println("<b>$n</b>")
    else if (xs.size > 1)
        println("<i>$n</i>")
    when {
        xs.isEmpty() -> println("<u>$n</u>")
        else -> {}
    }
    val b = xs.map { x ->
        x + 1
    }
    val c = "<s>$b</s>"
}
class Outer {
    private val x: Int?
    val y: String
    init { x = null; y = "" }
}
fun g(): String {
    val x: String?
    x = "a"
    return "<p>$x</p>"
}
fun h(cond: Boolean): String {
    val result = if (cond)
        "a"
    else
        "b"
    return "<p>$result</p>"
}
fun i(cond: Boolean): String {
    val result = when (cond)
    {
        true -> "a"
        false -> "b"
    }
    return "<p>$result</p>"
}
fun j(x: Any): String {
    val result = x as
        String
    return "<p>$result</p>"
}
fun k(map: Map<String, Int>): String {
    val result = map["a"]!!
    val other = map
        .getValue("b")
    return "<p>$result $other</p>"
}
fun l(x: Int): String {
    val y = if (x > 0) x else -x
    val z = while (false) {}
    val w = x.let { if (it > 0) it else 0 }
    return "<p>$y $w</p>"
}
}

// review309/cases2/r_if_expr_value
object Case52 {
fun f(ok: Boolean, name: String): String {
    val label = if (ok)
        name
    else
        "none"
    val tag = when (ok)
    {
        true -> "<b>"
        else -> "<i>"
    }
    val other = if (ok) name else "x"
    return "<p>$label $tag $other</p>"
}
fun g(xs: List<Int>): String {
    val firstBig = xs.firstOrNull { it > 3 }
        ?: return ""
    while (xs.isEmpty())
        println("x")
    val total = xs.sum()
    return "<p>$total</p>"
}
}

// review309/cases2/r_lambda_label
object Case53 {
fun f(n: String, rows: List<Int>) {
    for (n: Int in rows) rows.forEach lit@{ if (it > n) return@lit }
    println("<p>$n</p>")
}
}

// review309/cases2/r_multiline_header
object Case54 {
fun f(n: String, rows: List<Int>) {
    for (
        n: Int in rows
    ) println(n)
    println("<p>$n</p>")
}
fun g(n: String, rows: List<Pair<Int, Int>>) {
    for ((n, m) in rows.filter { (a, b) -> a > b }) println(n)
    println("<p>$n</p>")
}
}

// review309/cases2/r_while_do
object Case57 {
fun f(n: String, rows: List<Int>) {
    var i = 0
    do i++ while (i < 3)
    val a = n
    println("<p>$a</p>")
}
}

// review309/cases3/v_do_while_stmt
object Case63 {
fun f(n: String, rows: List<Int>) {
    for (n: Int in rows) do use(n) while (false)
    println("<p>$n</p>")
}
}

// review309/cases3/v_if_else_chain_stmt
object Case64 {
fun f(x: String, xs: List<Int>, a: Boolean) {
    if (a)
        for (x: Int in xs) if (x > 0) println(x) else println(-x)
    else
        println("<b>$x</b>")
    println("<p>$x</p>")
}
fun g(x: String, xs: List<Int>, a: Boolean) {
    if (a)
        for (x: Int in xs) if (x > 0) println(x)
    else
        println("<b>$x</b>")
}
}

// review309/cases3/v_nested_for_typed
object Case65 {
fun f(n: String, rows: List<Int>) {
    for (n: Int in rows)
        for (m: Int in rows)
            println(n + m)
    println("<p>$n</p>")
}
}

