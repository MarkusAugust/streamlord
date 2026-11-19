package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a value is built, read from the text, and what an editor's answers of its parts change. Each case is a Kotlin file
 * and the values it is told of.
 */
class ValueStructureTest {
    private val analyzer = Analyzer()
    private val core = "io.github.markusaugust.streamlord.core.domain"
    private val known = Modules.known(core = true, htmlDsl = true)

    /** The text of each value [src] is told of, as VS Code reads the file. */
    private fun told(src: String): List<String> =
        analyzer
            .analyzeKotlin(Source(src, SourceLanguage.KOTLIN, modules = known))
            .filter { it.code == IssueCode.UNESCAPED_INTERPOLATION || it.code == IssueCode.UNSAFE_INTERPOLATION }
            .map { src.substring(it.start, it.end) }

    /** The same with an editor that answers a String of every value but those of [answers], by their text. */
    private fun told(
        src: String,
        answers: Map<String, ValueAnswer>,
    ): List<String> =
        analyzer
            .analyzeKotlin(
                Source(src, SourceLanguage.KOTLIN, modules = known, valueAnswer = { s, e ->
                    val text = src.substring(s, e)
                    answers[text] ?: answers[text.trimStart('$').removeSurrounding("{", "}")] ?: ValueAnswer("kotlin.String")
                }),
            ).filter { it.code == IssueCode.UNESCAPED_INTERPOLATION || it.code == IssueCode.UNSAFE_INTERPOLATION }
            .map { src.substring(it.start, it.end) }

    @Test
    fun `a sum is markup beside its first literal only when that literal is HTML`() {
        assertEquals(listOf("\${\"Hi \" + name + \"<br>\"}"), told("fun f(name: String) = \"<p>\${\"Hi \" + name + \"<br>\"}</p>\""))
        assertEquals(
            listOf("\${g(n)}"),
            told("fun g(name: String) = \"Hi \" + name + \"<br>\"\nfun f(n: String) = \"<p>\${g(n)}</p>\""),
        )
        assertEquals(
            listOf("\$g"),
            told("fun f(name: String): String {\n    val g = \"Hi \" + name + \"<br>\"\n    return \"<p>\$g</p>\"\n}"),
        )
        assertEquals(listOf("name"), told("fun f(name: String) = \"<p>\${\"<br>\" + name}</p>\""))
    }

    @Test
    fun `a function's block is read past the brackets and quotes of its comments`() {
        val use = "\nfun f(n: String) = \"<p>\${g(n)}</p>\""
        assertEquals(
            listOf("\${g(n)}"),
            told("fun g(q: String): String {\n    if (q.isEmpty()) return \"<b>none</b>\" // }\n    return q\n}$use"),
        )
        assertEquals(
            listOf("\${g(n)}"),
            told(
                "fun g(q: String): String {\n    // don't\n    if (q.isEmpty()) {\n        return \"<b>none</b>\"\n    // it's\n    }\n    return q\n}$use",
            ),
        )
    }

    @Test
    fun `a call without a receiver means a member of its own class or a function of the top level`() {
        assertEquals(
            listOf("\${title()}"),
            told("class A { fun title(): String = \"<b>A</b>\" }\nclass B(val q: String) : Base() { fun page() = \"<p>\${title()}</p>\" }"),
        )
        assertEquals(
            listOf("\${badge()}"),
            told("fun other() {\n    fun badge() = \"<b>x</b>\"\n    badge()\n}\nfun f() = \"<p>\${badge()}</p>\""),
        )
        assertEquals(emptyList(), told("class A {\n    fun title(): String = \"<b>A</b>\"\n    fun page() = \"<p>\${title()}</p>\"\n}"))
    }

    @Test
    fun `a function is markup only when every function of its name is read and is`() {
        val use = "\nfun f(q: String) = \"<p>\${row(q)}</p>\""
        assertEquals(
            listOf("\${row(q)}"),
            told("fun row(x: Int): String = \"<b>\$x</b>\"\nfun <T : Comparable<T>> row(x: T): String = x.toString()$use"),
        )
        assertEquals(
            listOf("\${row(q)}"),
            told("fun row(q: String): String {\n    if (q.length > 3) return@row q\n    return \"<b>x</b>\"\n}$use"),
        )
        assertEquals(emptyList(), told("fun <T : Comparable<T>> row(x: T): String = \"<b>x</b>\"$use"))
    }

    @Test
    fun `a value is read to where its statement ends, past a comment and whatever a literal holds`() {
        assertEquals(
            listOf("\$x"),
            told(
                "fun f(q: String, ok: Boolean): String {\n    val x = \"<img src='//cdn/a.png'>\".takeIf { ok } ?: q\n    return \"<p>\$x</p>\"\n}",
            ),
        )
        assertEquals(
            listOf("\${g(n)}"),
            told(
                "fun g(q: String): String =\n    \"<b>x</b>\" // default\n        .replace(\"x\", q)\nfun f(n: String) = \"<p>\${g(n)}</p>\"",
            ),
        )
        // A line that starts with a sign starts a statement of its own.
        assertEquals(
            listOf("\$a"),
            told("fun f(q: String) = run {\n    val a = q\n    +\"<b>x</b>\"\n    \"<p>\$a</p>\"\n}"),
        )
    }

    @Test
    fun `buildString is markup where every statement of it starts by writing kotlinx html`() {
        val page = { body: String -> "fun f(q: String, c: Boolean, items: List<String>) = \"<p>\${buildString { $body }}</p>\"" }
        assertEquals(1, told(page("items.forEach { append(it); appendHTML().br() }")).size)
        assertEquals(1, told(page("if (c) append(q) else appendHTML().br()")).size)
        assertEquals(emptyList(), told(page("\n    appendHTML()\n        .br()\n    appendHTML().div { +q }\n")))
        assertEquals(emptyList(), told("fun f(q: String) = \"<p>\${StringBuilder().appendHTML().div { +q }.toString()}</p>\""))
        assertEquals(1, told("fun f(q: String) = \"<p>\${StringBuilder().append(q).appendHTML().br().toString()}</p>\"").size)
    }

    @Test
    fun `every function of the DSL that gives an expression is a helper`() {
        val helpers =
            "decrement delete deleteExpr get getExpr increment js not patch patchExpr peek peekExpr post postExpr put putExpr " +
                "query queryExpr set setAll setAllExpr setExpr signal statements toggle toggleAll"
        for (name in helpers.split(" ")) {
            assertEquals(emptyList(), told("fun f() = \"<b data-on:click=\\\"\${$name(\"a\")}\\\">x</b>\""), name)
        }
        assertEquals(1, told("fun f() = \"<b data-on:click=\\\"\${other(\"a\")}\\\">x</b>\"").size)
    }

    @Test
    fun `a helper is one only where the file and the editor find no other function of its name`() {
        val src = "class P {\n    fun page() = \"<p>\${get(\"title\")}</p>\"\n}"
        assertEquals(emptyList(), told(src))
        assertEquals(listOf("\${get(\"title\")}"), told("$src\nfun get(key: String): String = load(key)"))
        val elsewhere = { pkg: String ->
            mapOf(
                "get(\"title\")" to ValueAnswer("kotlin.String", declaration = "package $pkg\n\nfun get(key: String): String = load(key)"),
            )
        }
        assertEquals(listOf("\${get(\"title\")}"), told(src, elsewhere("com.acme")))
        assertEquals(emptyList(), told(src, elsewhere("io.github.markusaugust.streamlord.html")))
    }

    @Test
    fun `a helper's argument is what the editor says it is`() {
        val src =
            "fun f(row: Row) = \"<b data-text=\\\"\${signal(COUNT_KEY)}\\\" data-on:click=\\\"\${set(\"n\", row.count)}\\\" " +
                "data-show=\\\"\${signal(Config.NAME)}\\\">x</b>\""
        val answers =
            mapOf(
                "COUNT_KEY" to ValueAnswer("kotlin.String", constant = true),
                "row.count" to ValueAnswer("kotlin.Int"),
                "Config.NAME" to ValueAnswer("kotlin.String"),
            )
        assertEquals(listOf("\${signal(Config.NAME)}"), told(src, answers))
    }

    @Test
    fun `a helper's options are the author's own only where each statement sets one to the author's own`() {
        val page = { options: String -> "fun f(q: String) = \"<b data-on:click=\\\"\${post(\"/x\") { $options }}\\\">x</b>\"" }
        assertEquals(emptyList(), told(page("openWhenHidden = true; retryMaxCount = 3")))
        assertEquals(1, told(page("log(q)")).size)
        assertEquals(1, told(page("openWhenHidden = true\n    selector = q")).size)
    }

    @Test
    fun `a collection made of markup is one of markup, whatever makes it`() {
        for (maker in listOf("listOf", "listOfNotNull", "setOf", "sequenceOf", "arrayOf")) {
            assertEquals(emptyList(), told("fun f() = \"<ul>\${$maker(\"<li>a</li>\", \"<li>b</li>\").joinToString(\"\")}</ul>\""), maker)
            assertEquals(1, told("fun f(q: String) = \"<ul>\${$maker(\"<li>a</li>\", q).joinToString(\"\")}</ul>\"").size, maker)
        }
    }

    @Test
    fun `a function is markup by its mark, its type or every result, and a var is never`() {
        val use = "\nfun f(q: String) = \"<p>\${row(q)}</p>\""
        val head = "import io.github.markusaugust.streamlord.core.domain.Html\n"
        assertEquals(emptyList(), told("${head}fun row(q: String): Html = load(q)$use"))
        assertEquals(emptyList(), told("@Language(\"HTML\")\nprivate fun row(q: String): String = load(q)$use"))
        assertEquals(listOf("\${row(q)}"), told("// @Language(\"HTML\")\nfun row(q: String): String = load(q)$use"))
        assertEquals(emptyList(), told("fun row(q: String) = \"\"\"\n    <b>x</b>\n\"\"\".trimIndent()$use"))
        assertEquals(emptyList(), told("fun badge() = \"<b>x</b>\"\nfun f() = \"<p>\${badge().trimIndent()}</p>\""))
        assertEquals(listOf("\$row"), told("fun f(): String {\n    var row = \"<b>x</b>\"\n    return \"<p>\$row</p>\"\n}"))
    }

    @Test
    fun `a part is asked about where it is code, and not where a literal or a longer name spells it`() {
        val src =
            "fun f(n: Int, items: List<String>) = \"<b data-on:click=\\\"\${set(\"n\", n)}\\\">" +
                "\${items.map { it }.joinToString(\"\")}</b>\""
        val asked = analyzer.valueQuestions(Source(src, SourceLanguage.KOTLIN, modules = known))
        assertEquals(
            listOf(src.lastIndexOf("n)"), src.indexOf("it }")),
            listOf("n", "it").map { part -> asked.single { src.substring(it.start, it.end) == part }.start },
        )
    }

    @Test
    fun `a name the editor resolves to no constant is none inside a larger value`() {
        val src = "fun f(c: Boolean) = \"<p>\${Config.GREETING.trim()} \${if (c) Config.A else Config.B}</p>\""
        assertEquals(emptyList(), told(src))
        assertEquals(listOf("\${Config.GREETING.trim()}", "\${if (c) Config.A else Config.B}"), told(src, emptyMap()))
    }

    @Test
    fun `what a value is called on is markup by the type the editor gives it`() {
        val src =
            "fun f(repo: Repo, x: String, page: Page) = " +
                "\"<ul>\${repo.rows().joinToString(\"\")} \${render(x).text} \${page.header.text}</ul>\""
        val markup =
            mapOf(
                "repo.rows()" to ValueAnswer("kotlin.collections.List<$core.Html>"),
                "render(x)" to ValueAnswer("$core.Html"),
                "page.header" to ValueAnswer("$core.Html"),
            )
        assertEquals(emptyList(), told(src, markup))
        assertEquals(3, told(src, emptyMap()).size)
    }

    @Test
    fun `a part of a sum the editor types as another package's Html is no markup`() {
        val src = "import com.acme.*\nfun f(h: Html) = \"<p>\${\"by \" + h}</p>\""
        assertEquals(emptyList(), told(src, mapOf("h" to ValueAnswer("$core.Html"))))
        assertEquals(listOf("\${\"by \" + h}"), told(src, mapOf("h" to ValueAnswer("com.acme.Html"))))
    }

    @Test
    fun `a declaration the editor found is read where it stands, in this file or with the imports of its own`() {
        // A member reached through a receiver, which the text alone does not follow.
        val own = "class P {\n    fun badge() = \"<b>x</b>\"\n    fun outer() = badge()\n}\nfun f(p: P) = \"<p>\${p.outer()}</p>\""
        assertEquals(listOf("\${p.outer()}"), told(own, emptyMap()))
        assertEquals(emptyList(), told(own, mapOf("p.outer()" to ValueAnswer("kotlin.String", declaration = "fun outer() = badge()"))))
        val src = "fun f(u: User) = \"<main>\${card(u)} \${\"by \" + card(u)}</main>\""
        val card =
            "package parts\n\nimport io.github.markusaugust.streamlord.html.elements\n\n" +
                "fun card(u: User): String = elements { div { +u.name } }"
        assertEquals(emptyList(), told(src, mapOf("card(u)" to ValueAnswer("kotlin.String", declaration = card))))
        // The file's own function of that name is not the one the editor found.
        val shadowed = "fun card(u: User) = \"<b>x</b>\"\n$src"
        assertEquals(
            2,
            told(
                shadowed,
                mapOf("card(u)" to ValueAnswer("kotlin.String", declaration = "package parts\n\nfun card(u: User): String = u.name")),
            ).size,
        )
    }
}
