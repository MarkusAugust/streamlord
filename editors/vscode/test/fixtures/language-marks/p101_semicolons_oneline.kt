import org.intellij.lang.annotations.Language

@Language("HTML") val p101a = "R93 <p>a</p>"; val p101b = "R94 <p>b</p>"

@Language("HTML") fun p101c() = "R95 <p>c</p>"; fun p101d() = "R96 <p>d</p>"

fun p101e(): String { @Language("HTML") val x = "R97 <p>x</p>"; val y = "R98 y"; return x + y }

class P101C { @Language("HTML") val p = "R99 <p>p</p>"; val q = "R100 q" }

@Language("HTML") const val P101K = "R101 <p>k</p>"
const val P101L = "R102 l"

val p101m = listOf(@Language("HTML") "R103 <p>m</p>", "R104 n")
