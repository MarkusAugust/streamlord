import org.intellij.lang.annotations.Language
class P(val n: String) {
    @Language("HTML") val a: String get() = "Get <b>$n</b>"
    @get:Language("HTML") val b: String get() = "GetUse <b>$n</b>"
    val c: String @Language("HTML") get() = "GetMark <b>$n</b>"
}
