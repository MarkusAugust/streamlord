import org.intellij.lang.annotations.Language
@Language("HTML") fun r(xs: List<String>): String {
    xs.forEach { if (it.isEmpty()) return "Nonlocal <b>x</b>" }
    return xs.map { "Mapped <i>$it</i>" }.joinToString()
}
