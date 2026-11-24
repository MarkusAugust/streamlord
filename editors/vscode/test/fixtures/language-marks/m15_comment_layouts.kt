class P(val name: String) {
    // language=HTML
    val a: String
        get() = "Getter <b>$name</b>"

    // language=HTML
    var b: String = "Var <b>$name</b>"

    // language=HTML
    val c by lazy { "Lazy <b>$name</b>" }

    // language=HTML
    fun d(n: String): String {
        return "Ret <b>$n</b>"
    }

    // language=HTML
    fun e(n: String) =
        "Next <b>$n</b>"
}
