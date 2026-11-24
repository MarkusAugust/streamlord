import org.intellij.lang.annotations.Language

fun outer(m: String) {
    @Language("HTML")
    fun inner(n: String) = "Local <b>$n</b>"

    @Language("HTML")
    val local = "LocalVal <b>$m</b>"
    println(inner(m) + local)
}
