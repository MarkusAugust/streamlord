package forge.operands

class Tag {
    val out = StringBuilder()
    var title = ""

    operator fun String.unaryPlus() {
        out.append(this)
    }
}

fun div(block: Tag.() -> Unit): String = Tag().apply(block).out.toString()

// A plus at the start of a line in a block is a sign: the line before is a statement of its own.
fun statement(name: String) =
    div {
        name.length
        +"<p>$name</p>"
    }

fun assigned(name: String) =
    div {
        title = name
        +"<p>x</p>"
    }

// A plus at the end of a line joins the next, and inside brackets a line break ends nothing.
fun joined(name: String) =
    name +
        "<p>x</p>"

fun bracketed(name: String) = (
    name
        + "<p>x</p>"
)

fun <T> generic(a: T): String = a.toString()

fun generics(a: String) = generic<String>(a) + "<p>x</p>"

fun nested(a: String) = "<p>" + ("<b>" + a + "</b>") + "</p>"

fun numbers(a: String, n: Int) = a + n * 2 + "<p>${n.dec()}</p>"

fun compared(a: String, b: String) = "<p>" + a in setOf(b)

// What the reading cannot take apart is marked where it is certainly the value, with no rewrite.
fun unread(a: String) = "<p>" + object { override fun toString() = a } + "</p>"

fun card(title: String) = "<h2>${title.escaped()}</h2>"

fun String.escaped() = replace("<", "&lt;")
