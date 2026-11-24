import org.intellij.lang.annotations.Language

@Language("HTML")
fun String.wrap() = "Ext <b>$this</b>"

@Language("HTML")
val String.wrapped get() = "ExtProp <b>$this</b>"

@Language("HTML")
fun <T> gen(t: T) = "Gen <b>$t</b>"

@Language("HTML")
inline fun <reified T> gen2(t: T): String = "Gen2 <b>$t</b>"
