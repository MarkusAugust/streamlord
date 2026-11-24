fun p43f(a: String, b: String = "") = a + b

fun p43a(): String {
    // language=HTML;x
    return "j01 <p>a</p>"
}

fun p43b(): String {
    // language=HTML,CSS
    val s = "j02 <p>a</p>"
    return s
}

fun p43c(): String {
    /* language = HTML */
    return "j03 <p>a</p>"
}

fun p43d(): String {
    /* language=HTML;x */
    return "j04 <p>a</p>"
}

val p43e = /* language=HTML. */ "j05 <p>a</p>"
val p43g = /* language=HTML- */ "j06 <p>a</p>"
val p43h = /* language=HTML) */ "j07 <p>a</p>"
val p43i = // language=HTML;
    "j08 <p>a</p>"

fun p43j(c: Boolean): String = when {
    c -> "j09 x"
    else -> /* language=HTML; */ "j10 <p>b</p>"
}

fun p43k(c: Boolean): String {
    val s = "j11 x" + /* language=HTML */ /* more */
        "j12 <p>b</p>"
    return s
}

fun p43l(c: Boolean): String {
    val s = "j13 x"
        + /* language=HTML */ "j14 <p>b</p>" // missed: #347
    return s
}

fun p43m(c: Boolean): String = p43f(
    "j15 x" +
        /* language=HTML */ "j16 <p>b</p>",
    "j17 y",
)
