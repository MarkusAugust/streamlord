import org.intellij.lang.annotations.Language
import io.github.markusaugust.streamlord.core.domain.*
import io.github.markusaugust.streamlord.html.*
import org.springframework.web.bind.annotation.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// fresh309-a7f0/b.cases #111
object Case517 {
@Language("HTML") @JvmName("x")
@Suppress("this is a very long reason that pushes the literal beyond the reach of the mark which is two hundred and forty characters, so the literal after it is far away from the mark indeed and still more words") fun f(a: String) = "Far <b>$a</b>"
}

// fresh309-a7f0/b.cases #112
object Case518 {
fun f(@Language("HTML") @Named("b") body: String, t: String = "D1 $x", u: String = "D2 $x") = body
fun g(@Language("HTML") @Named("b") body: String = "D3 <b>$x</b>") = body
fun h(@Language("HTML") @Named(value = "b") @Other body: String, t: String = "D4 $x") = body
fun i(@Language("HTML") @Named @Other("v") body: String, t: String = "D5 $x") = body
fun j(@Language("HTML") @Named("b") val body: String, t: String = "D6 $x") = body
}

// fresh309-a7f0/b.cases #113
object Case519 {
fun f(@Language("HTML") @Named("b") body: String?, t: String = "D7 $x") = body
fun g(@Language("HTML") @Named("b") body: String = "D8, <b>$x</b>", t: String = "D9 $x") = body
}

// fresh309-a7f0/b.cases #114
object Case520 {
fun render(@Language("HTML") @Suppress("x") body: String) = body
fun use(name: String) = render("Arg <b>$name</b>")
}

// fresh309-a7f0/b.cases #115
object Case521 {
@Language("HTML") @JvmName("x") fun f(a: String) = "Unimported <b>$a</b>"
}

// fresh309-a7f0/b.cases #116
object Case522 {
val x = mapOf(@Suppress("x") "a" to 1)
@Language("HTML") val y = "Y <b>z</b>"
}

// fresh309-a7f0/b.cases #119
object Case525 {
fun f(x: Any) = x is @Ann("<b>q</b>") String
}

// review-309/app.kt: spring
object Case528 {
@RestController
@RequestMapping("/todos")
class TodoController(private val repo: TodoRepository) {
    @GetMapping("/{id}", produces = ["text/html"])
    @Language("HTML")
    fun show(@PathVariable id: Long): String {
        val todo = repo.find(id)!!
        val title = todo.title
        return """<li id="todo-$id" data-on:click="@post('/todos/$id/toggle')">$title</li>"""
    }

    @PostMapping("/{id}/toggle")
    fun toggle(@PathVariable id: Long, sse: SseEmitter): SseEmitter {
        val todo = repo.find(id) as Todo?
        val items = repo.all() as List<Todo>
        for (t in items) sse.patchElements("<li>${t.title}</li>")
        return sse
    }

    @Language("HTML")
    @GetMapping(produces = ["text/html"])
    fun list(name: String) = "Hello <b>$name</b>"

    @Language("HTML")
    @ResponseBody
    fun nested(name: String): String =
        if (name.isEmpty())
            "Nobody <i>here</i>"
        else
            "Hello <b>$name</b>"
}
}

// review-309/app.kt: ktor
object Case529 {
fun Application.routes() {
    routing {
        get("/") {
            val name = call.parameters["name"]!!
            val count = call.parameters["n"]?.toInt()
            call.respondText(
                // language=HTML
                "<p>Hello $name</p>",
                ContentType.Text.Html,
            )
        }
        get("/x") {
            @Language("HTML")
            val body = "Count <b>${call.parameters["n"]}</b>"
            call.respondText(body, ContentType.Text.Html)
        }
    }
}

@Language("HTML")
fun layout(title: String, body: String) =
    """
    <html><head><title>$title</title></head><body>$body</body></html>
    """.trimIndent()

@Language("HTML")
fun row(cells: List<String>): String = cells.joinToString("") { "<td>$it</td>" }
}

// review-309/crlf.kt
object Case532 {
fun f(q: String?) {
    val a = q!!
    val t = "<b>$a</b>"
}
fun outer(n: String) {
    fun size(n: Int) = pad(n)
    println("<p>$n</p>")
}
@Language("HTML") @JvmName("x")
fun hey(who: String) = "Hey <b>$who</b>"
fun g(q: Any) {
    val a = q as List<Int>
    val b = q as String?
    val t = "<b>$a$b</b>"
}
}

// review-309/lines.kt: getter-after-bangbang
object Case587 {
class P(val q: String?) {
    val a = q!!
    val b: String
        get() = "<b>$a</b>"
}
}

// review-309/lines.kt: getter-after-generic
object Case588 {
class P(val q: Any) {
    val a = q as List<String>
    val b: String
        get() = "<b>$a</b>"
}
}

// review-309/loops.kt: for-annotated
object Case610 {
fun f(n: String, rows: List<Int>) {
    for (n in rows) @Suppress("x") println(n)
    println("<p>$n</p>")
}
}

// review-309/marks.kt: if-value
object Case624 {
fun f(ok: Boolean, name: String) {
    @Language("HTML") val s = if (ok) "Hey <b>$name</b>" else "Bye <i>$name</i>"
    println(s)
}
}

// review-309/marks.kt: if-fun-expr
object Case625 {
@Language("HTML")
fun f(ok: Boolean, name: String): String = if (ok) "Hey <b>$name</b>" else "Bye <i>$name</i>"
}

// review-309/marks.kt: when-fun-expr
object Case626 {
@Language("HTML")
fun f(k: Int, name: String) = when (k) {
    1 -> "One <b>$name</b>"
    else -> "Other <i>$name</i>"
}
}

// review-309/marks.kt: call-arg-value
object Case627 {
@Language("HTML")
val page = render("Hey <b>x</b>")
}

// review-309/marks.kt: buildString
object Case628 {
@Language("HTML")
fun page(name: String) = buildString { append("Hey <b>$name</b>") }
}

// review-309/marks.kt: trimIndent
object Case629 {
@Language("HTML")
fun page(name: String) = """
    Hey <b>$name</b>
""".trimIndent()
}

// review-309/marks.kt: trimIndent-newline-eq
object Case630 {
@Language("HTML")
fun page(name: String) =
    """
    Hey <b>$name</b>
    """.trimIndent()
}

// review-309/marks.kt: paren-literal
object Case631 {
@Language("HTML")
val page = ("Hey <b>x</b>")
}

// review-309/marks.kt: marked-getter-block
object Case635 {
class P(val name: String) {
    @Language("HTML")
    val html: String
        get() {
            return "Hey <b>$name</b>"
        }
}
}

// review-309/marks.kt: marked-lazy
object Case636 {
class P(val name: String) {
    @Language("HTML")
    val html by lazy { "Hey <b>$name</b>" }
}
}

// review-309/marks.kt: marked-param-default
object Case637 {
fun card(@Language("HTML") body: String = "Hey <b>x</b>", title: String = "plain") = body
}

// review-309/marks.kt: marked-ctor-prop
object Case638 {
class Card(@Language("HTML") val body: String = "Hey <b>x</b>", val title: String = "plain")
}

// review-309/marks.kt: marked-param-after-ann
object Case639 {
fun card(@Language("HTML") @Suppress("x") body: String, title: String = "plain") = body
}

// review-309/marks.kt: marked-fun-default-arg
object Case640 {
@Language("HTML")
fun card(title: String = "plain title") = "Hey <b>$title</b>"
}

// review-309/marks.kt: marked-fun-no-literal
object Case641 {
@Language("HTML")
fun page() = layout()
val other = "plain text"
}

// review-309/marks.kt: marked-fun-long-body
object Case642 {
@Language("HTML")
fun page(name: String): String {
    val a = name.trim()
    val b = a.uppercase()
    val c = b.lowercase()
    val d = c.reversed()
    val e = d.reversed()
    val f2 = e.trim()
    val g = f2.trim()
    val h = g.trim()
    val i = h.trim()
    return "Hey <b>$i</b>"
}
}

// review-309/marks.kt: marked-fun-two-returns
object Case643 {
@Language("HTML")
fun page(name: String?): String {
    if (name == null) return "None <i>here</i>"
    return "Hey <b>$name</b>"
}
}

// review-309/marks.kt: marked-fun-return-call
object Case644 {
@Language("HTML")
fun page(name: String?): String {
    return wrap("Hey <b>$name</b>")
}
}

// review-309/marks.kt: marked-fun-labelled
object Case645 {
@Language("HTML")
fun page(items: List<String>): String {
    items.forEach { if (it.isEmpty()) return@forEach }
    return "Hey <b>${items.size}</b>"
}
}

// review-309/marks.kt: marked-fun-lambda-return
object Case646 {
@Language("HTML")
fun page(items: List<String>): String {
    val x = items.map { item -> "Item $item" }
    return "Hey <b>${x.size}</b>"
}
}

// review-309/marks.kt: marked-fun-local-fun
object Case647 {
@Language("HTML")
fun page(items: List<String>): String {
    fun row(s: String): String {
        return "Row $s"
    }
    return "Hey <b>${items.size}</b>"
}
}

// review-309/marks.kt: marked-get-annotation
object Case648 {
class P {
    @get:Language("HTML")
    val html: String = "Hey <b>x</b>"
}
}

// review-309/marks.kt: marked-fqn
object Case649 {
@org.intellij.lang.annotations.Language("HTML")
fun page(name: String) = "Hey <b>$name</b>"
}

// review-309/marks.kt: marked-value-named
object Case650 {
@Language(value = "HTML")
fun page(name: String) = "Hey <b>$name</b>"
}

// review-309/marks.kt: marked-prefix
object Case651 {
@Language("HTML", prefix = "<div>", suffix = "</div>")
fun page(name: String) = "Hey <b>$name</b>"
}

// review-309/marks.kt: marked-ann-nested
object Case652 {
@Language("HTML") @Deprecated("old", ReplaceWith("newPage(name)"))
fun page(name: String) = "Hey <b>$name</b>"
}

// review-309/marks.kt: marked-ann-nested-val
object Case653 {
@Language("HTML") @Deprecated("old", ReplaceWith("x"))
val page = "Hey <b>x</b>"
}

// review-309/marks.kt: marked-ann-nested-var
object Case654 {
@Language("HTML") @Deprecated("old", ReplaceWith("x"))
var page = "Hey <b>x</b>"
}

// review-309/marks.kt: marked-var-after-ann
object Case655 {
@Language("HTML") @Volatile
var page = "Hey <b>x</b>"
}

// review-309/marks.kt: marked-generic-fun
object Case656 {
@Language("HTML")
fun <T> page(item: T) = "Hey <b>$item</b>"
}

// review-309/marks.kt: marked-ext-fun
object Case657 {
@Language("HTML")
fun String.page() = "Hey <b>$this</b>"
}

// review-309/marks.kt: marked-not-string
object Case658 {
@Language("HTML")
val n = 3
val s = "plain"
}

// review-309/marks.kt: elvis
object Case659 {
fun f(x: String?) {
    @Language("HTML") val s = x ?: "Hey <b>none</b>"
}
}

// review-309/marks.kt: plus
object Case660 {
fun f(x: String) {
    @Language("HTML") val s = "Hey <b>" + x + "</b>"
}
}

// review-309/marks.kt: multi-dollar
object Case661 {
@Language("HTML")
fun f(x: String) = $$"Hey <b>$x $${x}</b>"
}

// review-309/marks.kt: language-other
object Case662 {
@Language("JSON")
val j = "{\"a\": 1}"
@Language("HTML")
val h = "Hey <b>x</b>"
}

// review-309/marks.kt: annotated-expr-literal
object Case665 {
@Language("HTML")
fun page(name: String) = @Suppress("x") ("Hey <b>$name</b>")
}

// review-309/marks.kt: mark-then-object
object Case666 {
object Pages {
    @Language("HTML")
    const val HEAD = "Head <b>x</b>"
    const val PLAIN = "plain"
}
}

// review-309/marks.kt: mark-on-class
object Case667 {
@Language("HTML")
class Page(val x: String = "Hey <b>x</b>")
}

// review-309/marks.kt: many-anns
object Case672 {
@Language("HTML") @JvmName("a") @JvmStatic @Suppress("b", "c") @Deprecated(message = "d")
fun page(name: String) = "Hey <b>$name</b>"
}

// review-309/marks.kt: ann-with-paren-in-string
object Case673 {
@Language("HTML") @Deprecated("use page(x) instead")
fun page(name: String) = "Hey <b>$name</b>"
}

// review-309/marks.kt: ann-with-paren-in-string-val
object Case674 {
@Language("HTML") @Deprecated("use page(x) instead")
var page = "Hey <b>x</b>"
}

// review-309/marks2.kt: val-when
object Case675 {
fun f(k: Int) {
    @Language("HTML") val s = when (k) { 1 -> "One <b>x</b>" else -> "other" }
    println(s)
}
}

// review-309/marks2.kt: fun-noparam-if
object Case676 {
@Language("HTML")
fun page() = if (ok) "Hey <b>x</b>" else "none"
}

// review-309/marks2.kt: getter-if
object Case677 {
class P(val ok: Boolean) {
    @Language("HTML")
    val s: String
        get() = if (ok) "Hey <b>x</b>" else ""
}
}

// review-309/marks2.kt: var-if
object Case678 {
fun f(ok: Boolean) {
    @Language("HTML") var s = if (ok) "Hey <b>x</b>" else ""
}
}

// review-309/marks2.kt: kdoc-paren-var
object Case680 {
class P {
    @Language("HTML")
    // the page (shown on top)
    var page = "Hey <b>x</b>"
}
}

