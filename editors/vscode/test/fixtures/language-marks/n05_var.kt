import org.intellij.lang.annotations.Language
@Language("HTML") var marked = "Var <b>x</b>"
fun f() { marked = "Assigned <i>x</i>" } // missed: #347
