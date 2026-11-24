import org.intellij.lang.annotations.Language

@Language("HTML")
fun s(n: String) = "Hey <b>" + n + "</b> there"

@Language("HTML")
val t = "Ho <b>" + load() + "</b>"
