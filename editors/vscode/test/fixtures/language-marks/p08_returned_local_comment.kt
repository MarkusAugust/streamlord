import org.intellij.lang.annotations.Language

@Language("HTML")
fun p08a(): String {
    val s = "Noted <i>a</i>" // why
    return s
}

@Language("HTML")
fun p08b(): String {
    val s = "Noted <i>b</i>" /* why */
    return s
}
