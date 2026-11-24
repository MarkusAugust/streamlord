import org.intellij.lang.annotations.Language
class Q(val n: String) {
    @Language("HTML") fun a() = "MemA <b>$n</b>"
    fun b() = "MemB <b>$n</b>"
    companion object { @Language("HTML") const val C = "Const <b>x</b>" }
}
