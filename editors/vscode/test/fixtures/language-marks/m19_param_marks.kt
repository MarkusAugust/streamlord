import org.intellij.lang.annotations.Language

fun f(@Language("HTML") html: String, other: String = "plain") = html

fun g(@Suppress("x") @Language("HTML") @Deprecated("y") html: String = "default <b>") = html

class K(@Language("HTML") val body: String = "Body <b>x</b>")

fun use() = f("Arg <b>x</b>", "not html")
