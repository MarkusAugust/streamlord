import org.intellij.lang.annotations.Language

@Language("HTML")
fun p07a(): String {
    val s = "Local <i>a</i>"
    return s
}

@Language("HTML")
fun p07b(ok: Boolean): String {
    var v = "Local <i>b</i>"
    if (ok) return v
    return "y"
}

@Language("HTML")
fun p07c(ok: Boolean): String {
    val inner = "Local <i>c</i>"
    return if (ok) "q" else inner
}

@Language("HTML")
fun p07d(t: String): String {
    val s = "Summed <i>d</i>"
    return s + t
}

@Language("HTML")
fun p07e(ok: Boolean): String {
    val s = if (ok) "Branch <i>e</i>" else "f"
    return s
}

@Language("HTML")
fun p07f(): String {
    var s = "Initial"
    s = "Later <i>f</i>"
    return s
}
