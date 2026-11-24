import org.intellij.lang.annotations.Language
@org.intellij.lang.annotations.Language("HTML") fun a(n: String) = "Qual <b>$n</b>"
@Language(value = "HTML", prefix = "<div>") fun b(n: String) = "Prefixed <b>$n</b>"
@Language("html") fun c(n: String) = "Lower <b>$n</b>"
