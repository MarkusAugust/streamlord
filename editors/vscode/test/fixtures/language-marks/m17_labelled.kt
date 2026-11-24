import org.intellij.lang.annotations.Language

@Language("HTML")
fun list(items: List<String>): String {
    items.forEach { if (it.isEmpty()) return@forEach }
    return "Items <b>x</b>"
}

@Language("HTML")
fun list2(items: List<String>): String = run label@{
    if (items.isEmpty()) return@label "None <b>x</b>"
    "Some <b>x</b>"
}
