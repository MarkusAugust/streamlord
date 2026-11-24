import org.intellij.lang.annotations.Language
fun foo(a: String, b: String) = a + b

fun c1(n: String): String {
    // language=html
    val a = "LowerC <b>$n</b>"
    // language=HTML
    val b = listOf(n).joinToString { "CLambda <b>$it</b>" }
    // language=HTML
    foo(n, "CArg <b>$n</b>")
    return a + b
}

// language=HTML
fun c2(n: String): String {
    val a = "InBody <b>$n</b>"
    return "RetBody <i>$n</i>"
}

fun c3(n: String) = foo(
    // language=HTML
    "ArgC <b>$n</b>",
    "ArgD <i>$n</i>",
)

fun c4(n: String): String {
    // language=SQL
    // language=HTML
    val a = "Two <b>$n</b>"
    /* language=HTML */ val b = "Block <b>$n</b>"
    // language=SQL
    val c = "Sql <b>$n</b>"
    return a + b + c
}
