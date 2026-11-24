import org.intellij.lang.annotations.Language

@Language("HTML")
@Suppress("unused")
@JvmName("hey")
fun hey(who: String) = "Hey <b>$who</b>"

@Suppress("x") @Language("HTML") @JvmField
val v = "Ho <b>x</b>"

@Language("HTML") @Deprecated("old", ReplaceWith("other()"))
fun dep(n: String) = "Dep <b>$n</b>"
