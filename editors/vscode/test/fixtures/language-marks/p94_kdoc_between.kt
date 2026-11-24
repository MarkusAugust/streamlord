@file:Suppress("R01 unused")

import org.intellij.lang.annotations.Language

/** R02 docs with "quotes" and <b>tags</b> */
@Language("HTML")
/** R03 kdoc after mark */
fun p94a(): String = "R04 <p>a</p>"

@Language("HTML") // R05 trailing comment
fun p94b(): String = "R06 <p>b</p>"

@Language("HTML")
/* block */ private inline fun p94c(): String = "R07 <p>c</p>"

@Language("HTML", prefix = "R08 <div>", suffix = "R09 </div>")
fun p94d(): String = "R10 <p>d</p>"

@Language(value = "HTML", prefix = "R11 <ul>")
val p94e = "R12 <li>e</li>"

@Deprecated("R13 old", ReplaceWith("R14 p94a()"))
@Language("HTML")
fun p94f(): String = "R15 <p>f</p>"

@Language("HTML")
@Deprecated(message = "R16 ${"R17 nested"} old")
fun p94g(): String = "R18 <p>g</p>"
