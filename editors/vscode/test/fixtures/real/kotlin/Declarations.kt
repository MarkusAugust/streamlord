import org.intellij.lang.annotations.Language

// What is written before a declaration, and where its value ends, as the editor reads them.

// A mark behind another annotation and a comment: nothing is said of $marked.
@Language("HTML")
@get:JvmName("markedName")
// the head of the page
val marked: String = load()

fun marks() = "<p>$marked</p>"

// A mark in a comment is none: $loose is told.
// @Language("HTML")
val loose: String = load()

fun comments() = "<p>$loose</p>"

// `open` in `door.open` is a name, and `open` behind an annotation is a modifier: $title is told, $plain is not.
abstract class Page(door: Door) {
    val isOpen = door.open
    val plain = "<b>x</b>"

    @Suppress("unused")
    open val title = "<b>x</b>"

    fun render() = "<p>$plain $title</p>"
}

// A getter far behind its val is one, and a function named get is none: $far is told, $near is not.
class Getters(val q: String) {
    val far: String = "<b>x</b>"
        @Since(Version(1))
        get() = field + q
    val near = "<b>x</b>"

    operator fun get(i: Int) = near

    fun page() = "<p>$far $near</p>"
}

// A call of get after a val is a route: nothing is said of $badge.
fun Route.routes() {
    val badge = "<b>x</b>"
    get("/") { call.respondText("<p>$badge</p>") }
}

// A function's parameters are in sight in the object its expression is: nothing is said of $n.
fun row(n: Int) = object : Row {
    fun cell() = "<p>$n</p>"
}

// A constructor behind an annotation with arguments declares its parameters: $name is told, $id is not.
class Line @Named("x") constructor(val id: Int, val name: String) {
    fun page() = "<p>$id $name</p>"
}

// Members on one line, and a private fun interface: $shown is told, since another may stand in for it.
open class Narrow { fun a() {} open val shown = "<b>x</b>"; fun page() = "<p>$shown</p>" }

// A function that returns to a label is not followed: ${labelled(rows)} is told.
fun labelled(rows: List<Int>): String {
    rows.forEach { return@forEach }
    return "<b>x</b>"
}

fun caller(rows: List<Int>) = "<p>${labelled(rows)}</p>"

// A parameter on the line after a comment is one: nothing is said of $heading, an Int.
fun commented(
    body: String, // the body
    heading: Int,
) = "<h1>$heading</h1>"

// A mark behind another annotation is the function's: nothing is said of "m03x", and $who is told.
@Language("HTML")
@get:JvmName("m03x")
val m03: String = "<i>x</i>"

@Language("HTML")
@JvmName("hey")
fun hey(who: String) = "Hey <b>$who</b>"

// A local function's parameter ends with its line: $page is told as the String it is.
fun outer(page: String) {
    fun size(page: Int) = load().length + page
    println("<p>$page</p>")
}

// A return in a function declared in another is that function's: nothing is said of ${stamped(n)}.
fun stamped(n: String): String {
    fun label(): String {
        return n
    }
    return "<b>x</b>"
}

fun stamping(n: String) = "<p>${stamped(n)}</p>"

// A catch names its variable for its block: $e there is the exception and is told, and after it the Int again.
fun caught(e: Int): String {
    try {
        load()
    } catch (e: IllegalStateException) {
        return "<b>$e</b>"
    }
    return "<i>$e</i>"
}

// A lambda's parameters are read past the comma after the last and a comment: nothing is said of $index and $count.
fun counted(rows: Map<Int, Int>) =
    rows.map {
        index: Int, // the key
        count: Int,
        ->
        "<li>$index: $count</li>"
    }
