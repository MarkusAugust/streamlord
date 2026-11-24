import org.intellij.lang.annotations.Language

@Language("HTML")
suspend fun s(n: String): String {
    delay(1)
    return "Suspend <b>$n</b>"
}

@Language("HTML")
private fun p(n: String) = "Private <b>$n</b>"
