import org.intellij.lang.annotations.Language

@Language("HTML")
val md = $$"""Hey <b>$$name</b> $plain"""

@Language("HTML")
fun md2(name: String) = $$"""Ho <b>$${name}</b>"""
