fun f(n: String, c: Boolean): String {
    // language=HTML
    val a = if (c) "CIf <b>$n</b>" else "CElse <i>$n</i>"
    // language=HTML
    return "CRet <b>$n</b>"
}

// language=HTML
fun g(n: String, c: Boolean) = if (c) "CFunIf <b>$n</b>" else "CFunElse"

// language=HTML
fun h(n: String) = listOf(n).joinToString { "CLambda <b>$it</b>" }
