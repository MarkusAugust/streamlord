import org.intellij.lang.annotations.Language

@Language("HTML")
val p84a = """
    Q52 <section>
      <h1>Hi</h1>
    </section>
""".trimIndent()

@Language("HTML")
val p84b = """
    |Q53 <nav>
    |  <a href="/">Home</a>
    |</nav>
""".trimMargin()

val p84c = """
    Q54 <div>not marked</div>
""".trimIndent()

@Language("HTML")
fun p84d(items: List<String>) = """
    Q55 <ul>
    ${items.joinToString("") { """Q56 <li>$it</li>""" }}
    </ul>
""".trimIndent()

fun p84e(): String {
    // language=HTML
    val x = """
        Q57 <form>
          <input name="q">
        </form>
    """
    return x.trimIndent()
}

@Language("HTML")
val p84f = """Q58 <p>${"$"}price</p>""".trimMargin("Q59 |")
