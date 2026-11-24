import org.intellij.lang.annotations.Language

fun p31id(s: String) = s

fun p31a(c: Boolean): String = when {
    c -> "n01 x"
    else -> "n02 x" + /* language=HTML */ "n03 <a>"
}

fun p31b(c: Boolean): String = when {
    c -> /* language=HTML */ "n04 <b>"
    else -> "n05 <c>"
}

fun p31c(c: Boolean): String = if (c) "n06 x" else /* language=HTML */ "n07 <d>"

fun p31d(c: Boolean): String = if (c) /* language=HTML */ "n08 <e>" else "n09 <f>"

fun p31e(c: String?): String = c ?: /* language=HTML */ "n10 <g>"

fun p31f(c: String?): String = /* language=HTML */ "n11 <h>" + c

fun p31g(c: String?): String = c + /* language=HTML */ "n12 <i>"

fun p31h(): String {
    val s = /* language=HTML */ "n13 <j>"
    return s
}

fun p31i(): String {
    val s = p31id("n14 x") + /* language=HTML */ "n15 <k>" + "n16 y"
    return s
}

fun p31j() {
    println("n17 x" + /* language=HTML */ "n18 <l>")
}

fun p31k(): String {
    return "n19 x".let { it + /* language=HTML */ "n20 <m>" }
}

val p31l = "n21 x" + /* language=XML */ "n22 <n>" // missed: #347
val p31m = "n23 x" + /* language=HTML */ 1 + "n24 <o>"
val p31n = "n25 x" + /* foo */ "n26 <p>"

@Language("HTML")
var p31v = ""

fun p31o(c: Boolean, s: String) {
    p31v = s + "n27 <q>" // missed: #347
    p31v = if (c) s + "n28 <r>" else "n29 <s>" // missed: #347
    p31v = when { c -> "n30 <t>"; else -> s + "n31 <u>" } // missed: #347
    p31v = when { c -> "n32 <v>"; else -> "n33 <w>" + s } // missed: #347
    p31v = when { c -> s + "n34 <x>"; else -> "n35 <y>" } // missed: #347
    p31v = when (s) { "n36 k" -> s + "n37 <z>"; else -> "n38 <a>" } // missed: #347
    p31v = if (c) "n39 <b>" else s + "n40 <c>" // missed: #347
    p31v = s.ifEmpty { "n41 <d>" } + "n42 <e>" // missed: #347
    p31v = p31id(s) + "n43 <f>" // missed: #347
    p31v = (s) + "n44 <g>" // missed: #347
    p31v = s.trim() + "n45 <h>" // missed: #347
}

@Language("HTML")
fun p31p(c: Boolean, s: String): String {
    return when { c -> "n46 <i>"; else -> s + "n47 <j>" }
}

@Language("HTML")
fun p31q(c: Boolean, s: String): String {
    return if (c) "n48 <k>" else s + "n49 <l>"
}

@Language("HTML")
fun p31r(c: Boolean, s: String): String {
    val t = when { c -> "n50 <m>"; else -> s + "n51 <n>" }
    return t
}

@Language("HTML")
fun p31s(c: Boolean, s: String): String = when (s) {
    "n52 k" -> s + "n53 <o>"
    else -> "n54 <p>"
}

@Language("HTML")
fun p31t(c: Boolean, s: String): String {
    var t = ""
    t = if (c) s + "n55 <q>" else "n56 <r>"
    return t
}

@Language("HTML")
val p31u: String = if (p31l.isEmpty()) p31l + "n57 <s>" else "n58 <t>"
