import org.intellij.lang.annotations.Language

@Language("HTML")
const val E = "Hey <b>x</b>"

object O {
    @Language("HTML") const val F = "Ho <b>x</b>"
    @Language("HTML") private const val G: String = "Hi <b>x</b>"
}
