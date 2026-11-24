import org.intellij.lang.annotations.Language

@Language("HTML")
fun p92a(items: List<String>) =
    if (items.isEmpty()) "P921 <p>None</p>"
    else items.joinToString(prefix = "P922 <ul>", postfix = "P923 </ul>") { "P924 <li>$it</li>" }

@Language("HTML")
fun p92b(x: String?) = x?.let { "P925 <b>$it</b>" } ?: "P926 <i>none</i>"

@Language("HTML")
fun p92c(n: Int) = try { "P927 <p>${100 / n}</p>" } catch (e: ArithmeticException) { "P928 <p>err</p>" }

@Language("HTML")
fun p92d(s: String) = s.takeIf { it.isNotBlank() } ?: "P929 default"

@Language("HTML")
fun p92e(flag: Boolean): String = (if (flag) "Q150 <hr>" else "Q151 <br>")

@Language("HTML")
fun p92f(s: String) = "Q152 <pre>" + s.replace("Q153 <", "Q154 &lt;") + "Q155 </pre>"

@Language("HTML")
fun p92g(map: Map<String, Int>) = map.map { (k, v) -> "Q156 <p>$k=$v</p>" }.joinToString("Q157 ")

@Language("HTML")
fun p92h() = listOf("Q158 a", "Q159 b").joinToString { "Q160 <i>$it</i>" }
