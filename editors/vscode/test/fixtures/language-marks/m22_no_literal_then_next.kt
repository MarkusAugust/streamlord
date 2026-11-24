import org.intellij.lang.annotations.Language

@Language("HTML")
val a = load()
val b = "next decl"

@Language("HTML")
fun c(n: String) = n
val d = "after fun"
