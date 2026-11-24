import org.intellij.lang.annotations.Language
fun d(@Language("HTML") body: String = "Default <b>x</b>", other: String = "Other <i>x</i>") = body
