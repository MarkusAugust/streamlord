import org.intellij.lang.annotations.Language

@Language("HTML")
fun p82table(rows: List<Pair<String, Int>>): String = buildString {
    append("Q32 <table>")
    for ((k, v) in rows) {
        append("Q33 <tr><td>$k</td><td>$v</td></tr>")
    }
    appendLine("Q34 </table>")
}

fun p82plain(): String = buildString {
    @Language("HTML")
    val open = "Q35 <ul>"
    append(open)
    append("Q36 <li>x</li>")
}

@Language("HTML")
fun p82sb(): String {
    val sb = StringBuilder()
    sb.append("Q37 <div>")
    sb.append("Q38 </div>")
    return sb.toString()
}

@Language("HTML")
fun p82ret(): String {
    return buildString { append("Q39 <i>x</i>") }
}
