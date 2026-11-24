import org.intellij.lang.annotations.Language

@Language("HTML")
fun p71a(c: Boolean): String {
    if (c) throw IllegalStateException(if (c) "Q01 a" else "Q02 b")
    return if (c) "Q03 <p>a</p>" else "Q04 <p>b</p>"
}

@Language("HTML")
fun p71b(c: Boolean, d: Boolean): String {
    return if (c) "Q05 <p>a</p>" else if (d) "Q06 <p>b</p>" else "Q07 <p>c</p>"
}

@Language("HTML")
fun p71c(c: Boolean): String {
    do if (c) println("Q08 p") while (false)
    return if (c) "Q09 <p>a</p>" else "Q10 <p>b</p>"
}

@Language("HTML")
fun p71d(c: Boolean): String {
    return try { if (c) "Q11 <p>a</p>" else "Q12 <p>b</p>" } catch (e: Exception) { "Q13 <p>c</p>" }
}

@Language("HTML")
fun p71e(c: Boolean, n: Int): String {
    return when (if (c) 1 else n) { 1 -> "Q14 <p>a</p>"; else -> "Q15 <p>b</p>" }
}

@Language("HTML")
fun p71f(c: Boolean): String {
    val l = listOf(1).map f@{
        return@f if (c) "Q16 a" else "Q17 b"
    }
    return if (c) "Q18 <p>a</p>" else "Q19 <p>b</p>"
}

@Language("HTML")
fun p71g(c: Boolean): String {
    return@p71g if (c) "Q20 <p>a</p>" else "Q21 <p>b</p>"
}

@Language("HTML")
fun p71h(c: Boolean): String {
    l@ while (true) { if (c) break@l }
    return if (c) "Q22 <p>a</p>" else "Q23 <p>b</p>"
}

@Language("HTML")
fun p71i(c: Boolean): String {
    val x = l@ if (c) "Q24 a" else "Q25 b"
    return if (c) "Q26 <p>a</p>" else x
}

@Language("HTML")
fun p71j(c: Boolean): String {
    if (c) return "Q27 <p>a</p>" else if (!c) return "Q28 <p>b</p>"
    return "Q29 <p>c</p>"
}

@Language("HTML")
fun p71k(c: Boolean): String {
    when { c -> if (c) return "Q30 <p>a</p>" }
    return when (c) { true -> if (c) "Q31 <p>b</p>" else "Q32 <p>c</p>"; else -> "Q33 <p>d</p>" }
}

@Language("HTML")
fun p71l(c: Boolean): String {
    throw if (c) IllegalStateException("Q34 a") else RuntimeException("Q35 b")
}

@Language("HTML")
fun p71m(c: Boolean): String {
    val e = try { 1 } finally { if (c) println("Q36 p") }
    return if (c) "Q37 <p>a</p>" else "Q38 <p>b</p>"
}

@Language("HTML")
fun p71n(c: Boolean): String {
    return when { c -> "Q39 <p>a</p>"; else -> if (c) "Q40 <p>b</p>" else "Q41 <p>c</p>" }
}

@Language("HTML")
fun p71o(c: Boolean): String {
    loop@ for (i in 0..1) { if (c) continue@loop; if (c) return "Q42 <p>a</p>" }
    return if (c) "Q43 <p>b</p>" else "Q44 <p>c</p>"
}

@Language("HTML")
fun p71p(c: Boolean): String {
    val x = c
    return if (x) "Q45 <p>a</p>" else "Q46 <p>b</p>"
}

@Language("HTML")
fun p71q(c: Boolean, s: String?): String {
    s ?: return if (c) "Q47 <p>a</p>" else "Q48 <p>b</p>"
    return s
}

@Language("HTML")
fun p71r(c: Boolean): String {
    if (c) return run { if (c) "Q49 a" else "Q50 b" }
    return if (c) "Q51 <p>a</p>" else "Q52 <p>b</p>"
}
