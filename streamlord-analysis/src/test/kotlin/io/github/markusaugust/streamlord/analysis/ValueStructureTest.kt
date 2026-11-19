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
        assertEquals(emptyList(), told(own, mapOf("p.outer()" to ValueAnswer("kotlin.String", declaredAt = own.indexOf("fun outer")))))
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

    @Test
    fun `a declaration of the judged file is read where the editor says it stands, whatever else is spelled the same`() {
        val src =
            "class B(@Language(\"HTML\") val body: String)\nclass A(val body: String)\n" +
                "fun page(a: A, b: B) = \"<div>\${a.body} \${b.body}</div>\""
        val answers =
            mapOf(
                "a.body" to ValueAnswer("kotlin.String", declaredAt = src.indexOf("val body: String)\nfun")),
                "b.body" to ValueAnswer("kotlin.String", declaredAt = src.indexOf("@Language")),
            )
        assertEquals(listOf("\${a.body}"), told(src, answers))
    }

    @Test
    fun `what a val or a function of the file is set to is asked about where it is written`() {
        val badge = ValueAnswer("kotlin.String", declaration = "package parts\n\nfun badge(): String = \"<b>new</b>\"")
        val row = "fun f(): String {\n    val row = badge()\n    return \"<div>\$row</div>\"\n}"
        assertEquals(listOf("\$row"), told(row, emptyMap()))
        assertEquals(emptyList(), told(row, mapOf("badge()" to badge)))
        val outer = "fun outer() = badge()\nfun f() = \"<div>\${outer()}</div>\""
        assertEquals(listOf("\${outer()}"), told(outer, emptyMap()))
        assertEquals(emptyList(), told(outer, mapOf("badge()" to badge)))
        val text = "fun f(page: Page): String {\n    val x = page.header.text\n    return \"<div>\$x</div>\"\n}"
        assertEquals(emptyList(), told(text, mapOf("page.header" to ValueAnswer("$core.Html"))))
    }

    @Test
    fun `the annotation marks HTML however it is written`() {
        val forms =
            listOf(
                "@Language(\"HTML\", prefix = \"<div>\")",
                "@Language(value = \"HTML\")",
                "@org.intellij.lang.annotations.Language(\"html\")",
            )
        for (form in forms) {
            assertEquals(emptyList(), told("fun page($form a: String) = \"<div>\$a</div>\""), form)
            assertEquals(emptyList(), told("$form fun a(): String = load()\nfun page() = \"<div>\${a()}</div>\""), form)
        }
        assertEquals(
            emptyList(),
            told("class C(@param:Language(\"HTML\") val body: String) {\n    fun page() = \"<div>\$body</div>\"\n}"),
        )
        assertEquals(listOf("\$a"), told("fun page(@Language(\"JSON\") a: String) = \"<div>\$a</div>\""))
    }

    @Test
    fun `a declaration that shows no markup leaves what the value itself shows`() {
        val elements =
            "package io.github.markusaugust.streamlord.html\n\n" +
                "public inline fun elements(block: TagConsumer<*>.() -> Unit): String = StringBuilder().also { it.block() }.toString()"
        val src = "import io.github.markusaugust.streamlord.html.elements\nfun f(q: String) = \"<p>\${elements { span { +q } }}</p>\""
        assertEquals(emptyList(), told(src, mapOf("elements { span { +q } }" to ValueAnswer("kotlin.String", declaration = elements))))
        // Of a compiled function the editor gives the package alone.
        val built = "fun f(q: String) = \"<p>\${createHTML().div { +q }}</p>\""
        assertEquals(
            emptyList(),
            told(
                built,
                mapOf("createHTML().div { +q }" to ValueAnswer("kotlin.String", declaration = "package kotlinx.html\n")),
            ),
        )
        val helper = "fun f() = \"<b data-on:click=\\\"\${get(\"title\")}\\\">x</b>\""
        assertEquals(1, told(helper, mapOf("get(\"title\")" to ValueAnswer("kotlin.String", declaration = "package com.lib\n"))).size)
    }

    /** [told] with an editor that answers by where it is asked: [types] by where the part starts, a String anywhere else. */
    private fun toldAt(
        src: String,
        types: Map<Int, String>,
    ): List<String> =
        analyzer
            .analyzeKotlin(
                Source(src, SourceLanguage.KOTLIN, modules = known, valueAnswer = { s, _ -> ValueAnswer(types[s] ?: "kotlin.String") }),
            ).filter { it.code == IssueCode.UNESCAPED_INTERPOLATION || it.code == IssueCode.UNSAFE_INTERPOLATION }
            .map { src.substring(it.start, it.end) }

    @Test
    fun `a part is not the name after a dot, and a name that means two things is not answered for`() {
        val selector = "fun f(user: User, id: String) = \"<b data-on:click=\\\"\${post(\"/users/\${user.id}/items/\$id\")}\\\">x</b>\""
        val member = selector.indexOf("user.id")
        assertEquals(1, toldAt(selector, mapOf(member to "kotlin.Int", member + 5 to "kotlin.Int")).size)
        val lambdas =
            "fun f(c: Boolean, rows: List<Html>, labels: List<Label>) = " +
                "\"<ul>\${if (c) rows.joinToString(\"\") { it.text } else labels.joinToString(\"\") { it.text }}</ul>\""
        val first = lambdas.indexOf("it.text")
        val second = lambdas.lastIndexOf("it.text")
        assertEquals(1, toldAt(lambdas, mapOf(first to "$core.Html")).size)
        assertEquals(emptyList(), toldAt(lambdas, mapOf(first to "$core.Html", second to "$core.Html")))
        val named = "fun f(rows: List<Html>) = \"<ul>\${rows.joinToString(\"\") { r -> r.text }}</ul>\""
        assertEquals(emptyList(), toldAt(named, mapOf(named.indexOf("r.text") to "$core.Html")))
        assertEquals(1, toldAt(named, mapOf(named.indexOf("r ->") to "$core.Html")).size)
    }

    @Test
    fun `a function another may stand in for is not followed`() {
        val local = "fun badge() = \"<b>x</b>\"\nfun f(q: String): String {\n    fun badge() = q\n    return \"<p>\${badge()}</p>\"\n}"
        assertEquals(listOf("\${badge()}"), told(local))
        assertEquals(listOf("\${badge()}"), told("fun badge() = \"<b>x</b>\"\nfun f(badge: () -> String) = \"<p>\${badge()}</p>\""))
        val open =
            "abstract class Page(val q: String) {\n    open fun head(): String = \"<title>x</title>\"\n" +
                "    fun render() = \"<html>\${head()}</html>\"\n}"
        assertEquals(listOf("\${head()}"), told(open))
        assertEquals(emptyList(), told(open.replace("open fun", "fun")))
        assertEquals(
            listOf("\${head()}"),
            told("interface Page {\n    fun head(): String = \"<b>x</b>\"\n    fun render() = \"<p>\${head()}</p>\"\n}"),
        )
        val overriding =
            "class Card : Base() {\n    override fun head(): String = \"<b>x</b>\"\n" +
                "    fun render() = \"<p>\${head()}</p>\"\n}"
        assertEquals(emptyList(), told(overriding))
        assertEquals(listOf("\${head()}"), told("open $overriding"))
        assertEquals(
            listOf("\$head"),
            told("open class Page {\n    open val head: String = \"<title>x</title>\"\n    fun render() = \"<html>\$head</html>\"\n}"),
        )
    }

    @Test
    fun `what kotlinx html wrote is markup only where nothing else is written after it`() {
        assertEquals(1, told("fun f(q: String) = \"<p>\${StringBuilder().appendHTML().div { }.append(q).toString()}</p>\"").size)
        assertEquals(1, told("fun f(q: String) = \"<p>\${buildString { appendHTML().div { }.append(q) }}</p>\"").size)
        assertEquals(1, told("fun f(q: String) = \"<p>\${buildString {\n    appendHTML().br()\n        .append(q)\n}}</p>\"").size)
    }

    @Test
    fun `a lambda that returns to its label gives more than its last line`() {
        assertEquals(
            1,
            told(
                "fun f(q: String, rows: List<String>) = " +
                    "\"<ul>\${rows.joinToString(\"\") { if (it == q) return@joinToString q; \"<li>x</li>\" }}</ul>\"",
            ).size,
        )
    }

    @Test
    fun `a helper is one where the call can mean no function of the file`() {
        val src = "class Cache { operator fun get(k: String): String = k }\nfun f() = \"<b data-on:click=\\\"\${get(\"/x\")}\\\">x</b>\""
        assertEquals(emptyList(), told(src))
    }
}
