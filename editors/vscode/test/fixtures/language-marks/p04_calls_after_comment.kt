fun routes() {
    // language=HTML
    val page = "Page <b>x</b>"
    get("route one") { page }
    set("route two") { page }
    @Suppress("x") get("annotated call") { page }
}

fun get(path: String, f: () -> String) = f()

fun set(path: String, f: () -> String) = f()

class S {
    // language=HTML
    var v: String = "Var <b>x</b>"
        set(value) {
            field = "in setter" + value
        }
}
