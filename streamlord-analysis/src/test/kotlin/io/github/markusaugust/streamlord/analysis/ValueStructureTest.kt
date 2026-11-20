package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.fixtures.assertGrowsLinearly
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
                "fun g(q: String): String {\n    // don't\n    if (q.isEmpty()) {\n        return \"<b>none</b>\"\n" +
                    "    // it's\n    }\n    return q\n}$use",
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
                "fun f(q: String, ok: Boolean): String {\n    val x = \"<img src='//cdn/a.png'>\".takeIf { ok } ?: q\n" +
                    "    return \"<p>\$x</p>\"\n}",
            ),
        )
        assertEquals(
            listOf("\${g(n)}"),
            told(
                "fun g(q: String): String =\n    \"<b>x</b>\" // default\n        .replace(\"x\", q)\n" +
                    "fun f(n: String) = \"<p>\${g(n)}</p>\"",
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
        assertEquals(emptyList(), told(own, mapOf("p.outer()" to ValueAnswer("kotlin.String", declaredAt = own.indexOf("outer")))))
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
                "a.body" to ValueAnswer("kotlin.String", declaredAt = src.indexOf("body: String)\nfun")),
                "b.body" to ValueAnswer("kotlin.String", declaredAt = src.indexOf("body")),
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

    @Test
    fun `the questions list what an answer leads on to`() {
        val src = "class P {\n    fun outer() = loose()\n}\nfun f(p: P) = \"<p>\${p.outer()}</p>\""
        val source = Source(src, SourceLanguage.KOTLIN, modules = known)
        val asked = { s: Source -> analyzer.valueQuestions(s).map { src.substring(it.start, it.end) } }
        assertEquals(listOf("\${p.outer()}"), asked(source))
        val placed =
            source.copy(valueAnswer = { s, _ ->
                ValueAnswer("kotlin.String", declaredAt = src.indexOf("outer")).takeIf {
                    src[s] ==
                        '$'
                }
            })
        assertEquals(listOf("\${p.outer()}", "loose()"), asked(placed))
    }

    @Test
    fun `a declaration is read only where its name is one the text reads`() {
        val here = "fun `my plain`(q: String) = q\nval after = \"<b>x</b>\"\nfun f(q: String) = \"<p>\${`my plain`(q)}</p>\""
        assertEquals(1, told(here, mapOf("`my plain`(q)" to ValueAnswer("kotlin.String", declaredAt = here.indexOf("`my")))).size)
        val src = "fun f(q: String, l: List<String>) = \"<p>\${`my plain`(q)} \${l.render(q)}</p>\""
        val quoted = "package parts\n\nfun `my plain`(q: String): String {\n    val open = \"<b>\"\n    return q\n}"
        val bounded =
            "package parts\n\nfun <T : Comparable<T>> List<T>.render(q: String): String {\n    val open = \"<b>\"\n" +
                "    return q\n}"
        val answers =
            mapOf(
                "`my plain`(q)" to ValueAnswer("kotlin.String", declaration = quoted),
                "l.render(q)" to ValueAnswer("kotlin.String", declaration = bounded),
            )
        assertEquals(listOf("\${`my plain`(q)}", "\${l.render(q)}"), told(src, answers))
    }

    @Test
    fun `a member of another file is read in the class the editor shows it in`() {
        val src = "fun f(c: Card) = \"<p>\${c.badge()}</p>\""
        val within = { head: String, member: String ->
            mapOf("c.badge()" to ValueAnswer("kotlin.String", declaration = "package parts\n\n$head {\n$member\n}"))
        }
        assertEquals(listOf("\${c.badge()}"), told(src, within("interface Card", "fun badge() = \"<b>x</b>\"")))
        assertEquals(listOf("\${c.badge()}"), told(src, within("open class Card", "override fun badge() = \"<b>x</b>\"")))
        assertEquals(emptyList(), told(src, within("class Card", "override fun badge() = \"<b>x</b>\"")))
        assertEquals(emptyList(), told(src, within("class Card", "fun badge() = \"<b>x</b>\"")))
    }

    @Test
    fun `a parameter is read behind a comment at the end of the line before it`() {
        assertEquals(emptyList(), told("fun f(\n    a: String, // the a\n    title: Int,\n) = \"<p>\$title</p>\""))
        val row = "class Row(\n    val id: String, // the key\n    val title: String,\n)"
        assertEquals(setOf("id", "title"), declaredNames(row).others)
        assertEquals(emptyList(), told("fun f(\n    // the body\n    @Language(\"HTML\") body: String,\n) = \"<div>\$body</div>\""))
        assertEquals(emptyList(), told("fun f(\n    @Suppress(\"x\") // why\n    title: Int,\n) = \"<p>\$title</p>\""))
        // A mark in a comment is none, and a comma in one cuts nothing.
        val like = "fun f(\n    a: String, // like @Language(\"HTML\") here\n    title: String,\n) = \"<p>\$title</p>\""
        assertEquals(listOf("\$title"), told(like))
        assertEquals(
            emptySet(),
            fileFacts(like)
                .functions
                .getValue("f")
                .single()
                .html,
        )
        val marked = "fun g(a: String, // one, two\n    @Language(\"HTML\") body: String) = body"
        assertEquals(
            setOf(1, "body"),
            fileFacts(marked)
                .functions
                .getValue("g")
                .single()
                .html,
        )
        assertEquals(
            listOf("\$title"),
            told("fun f(\n    a: String,\n" + "    // old\n".repeat(5000) + "    title: String,\n) = \"<p>\$title</p>\""),
        )
    }

    @Test
    fun `a comment is left out of the parameters whatever else the file holds`() {
        val after = "fun f(\n    a: String, // the a\n    title: Int,\n) = \"<p>\$title</p>\""
        for (before in listOf(
            "/** A row \uD83D\uDE00\uD83D\uDE00 */\n",
            "/* outer /* inner */ still \"outer */\n",
            "class T {\n    fun `it's here`() = check(\"<p>Don't</p>\")\n}\n",
            "val a = \"abc\n",
        )) {
            assertEquals(emptyList(), told(before + after), before)
        }
        assertEquals(setOf("id", "title"), declaredNames("/** \uD83D\uDE00 */\ndata class Row(val id: String, val title: String)").others)
        assertEquals(emptyList(), told("fun f(\n    /* outer /* inner */ still outer */ title: Int,\n) = \"<p>\$title</p>\""))
        // A comment against a literal is one too.
        val glued = "fun f(a: String = \"x\"/* @Language(\"HTML\") */, b: String) = b"
        assertEquals(
            emptySet(),
            fileFacts(glued)
                .functions
                .getValue("f")
                .single()
                .html,
        )
        val inside = "fun g(@Language(/* c */\"HTML\") body: String) = body"
        assertEquals(
            setOf(0, "body"),
            fileFacts(inside)
                .functions
                .getValue("g")
                .single()
                .html,
        )
        // A string that holds `//` or a comma is no comment and cuts nothing.
        val link = "fun g(x: String = \"http://a, b\", @Language(\"HTML\") body: String) = body"
        assertEquals(
            setOf(1, "body"),
            fileFacts(link)
                .functions
                .getValue("g")
                .single()
                .html,
        )
    }

    @Test
    fun `a parameter is marked HTML behind a comment too`() {
        assertEquals(
            emptyList(),
            told("class K(/** The body. */ @Language(\"HTML\") val body: String) {\n    fun page() = \"<div>\$body</div>\"\n}"),
        )
    }

    @Test
    fun `a function that takes markup by a marked parameter is not followed`() {
        assertEquals(
            listOf("\${cell(q)}"),
            told("fun cell(@Language(\"HTML\") inner: String) = \"<td>\$inner</td>\"\nfun f(q: String) = \"<tr>\${cell(q)}</tr>\""),
        )
        val elsewhere =
            ValueAnswer(
                "kotlin.String",
                declaration = "package parts\n\nfun cell(@Language(\"HTML\") inner: String) = \"<td>\$inner</td>\"",
            )
        assertEquals(listOf("\${cell(q)}"), told("fun f(q: String) = \"<tr>\${cell(q)}</tr>\"", mapOf("cell(q)" to elsewhere)))
    }

    @Test
    fun `whether another may stand in for a member is read off its class and its own modifiers, in code alone`() {
        val page = "interface Page {\n    fun head(): String = \"<b>x</b>\"\n    fun render() = \"<p>\${head()}</p>\"\n}"
        assertEquals(listOf("\${head()}"), told("/** A class of pages. */\n$page"))
        assertEquals(listOf("\${head()}"), told("data class Row(val a: Int)\n$page"))
        val card = "open class Card : Base() {\n    override fun head(): String = \"<b>x</b>\"\n    fun render() = \"<p>\${head()}</p>\"\n}"
        assertEquals(listOf("\${head()}"), told("class Row(val a: Int)\n$card"))
        assertEquals(listOf("\${head()}"), told("@Serializable(with = CardSerializer::class)\n$card"))
        val final =
            "class Card {\n    // whether the card is open\n    fun head(): String = \"<b>x</b>\"\n" +
                "    fun render() = \"<p>\${head()}</p>\"\n}"
        assertEquals(emptyList(), told(final))
        assertEquals(
            listOf("\${head()}"),
            told(final.replace("    fun head", "    open @JvmName(\"h\") fun head").replace("class Card", "open class Card")),
        )
        val body = "Card : Base() {\n    override val body = \"<b>x</b>\"\n    fun render() = \"<p>\$body</p>\"\n}"
        assertEquals(emptyList(), told("object $body"))
        assertEquals(emptyList(), told("class $body"))
        assertEquals(listOf("\$body"), told("abstract class $body"))
        assertEquals(
            listOf("\${items.joinToString(\"\")}"),
            told(
                "open class P {\n    open val items = listOf(\"<li>a</li>\")\n" +
                    "    fun render() = \"<ul>\${items.joinToString(\"\")}</ul>\"\n}",
            ),
        )
    }

    @Test
    fun `a function of the top level is followed only where no class of the file has a member of its name`() {
        val src =
            "fun badge() = \"<b>x</b>\"\nclass A(val q: String) {\n    companion object {\n        fun badge(): String = load()\n    }\n" +
                "    fun page() = \"<p>\${badge()}</p>\"\n}"
        assertEquals(listOf("\${badge()}"), told(src))
    }

    @Test
    fun `a value of a helper's name is no function, and the editor's word on the call is taken over the file's`() {
        val named =
            "fun f(): String {\n    val signal = signal(\"count\")\n" +
                "    return \"<b data-text=\\\"\${signal(\"count\")}\\\">x</b>\"\n}"
        assertEquals(emptyList(), told(named))
        assertEquals(
            emptyList(),
            told("class PostView(private val post: Post) {\n    fun like() = \"<b data-on:click=\\\"\${post(\"/like\")}\\\">x</b>\"\n}"),
        )
        val member =
            "class P {\n    fun get(k: String): String = load(k)\n" +
                "    fun page() = \"<b data-on:click=\\\"\${get(\"/x\")}\\\">x</b>\"\n}"
        assertEquals(listOf("\${get(\"/x\")}"), told(member))
        val dsl = ValueAnswer("kotlin.String", declaration = "package io.github.markusaugust.streamlord.html\n")
        assertEquals(emptyList(), told(member, mapOf("get(\"/x\")" to dsl)))
    }

    @Test
    fun `a number that may be null is a number left of an elvis, whoever says what it is`() {
        val src = "fun f(count: Int?) = \"<b data-on:click=\\\"\${set(\"n\", count ?: 0)}\\\">x</b>\""
        assertEquals(emptyList(), told(src))
        assertEquals(emptyList(), told(src, mapOf("count" to ValueAnswer("kotlin.Int?"), "count ?: 0" to ValueAnswer("kotlin.Int"))))
    }

    @Test
    fun `a comment's apostrophes join no statements, and a val with a getter is what the getter gives`() {
        assertEquals(
            listOf("\${buildString {\n    appendHTML().br() // it's\n    append(q) // it's\n}}"),
            told("fun f(q: String) = \"<p>\${buildString {\n    appendHTML().br() // it's\n    append(q) // it's\n}}</p>\""),
        )
        assertEquals(
            listOf("\$title"),
            told(
                "class P(val q: String) {\n    val title: String = \"<b>x</b>\"\n        get() = field + q\n" +
                    "    fun page() = \"<p>\$title</p>\"\n}",
            ),
        )
    }

    @Test
    fun `a part is judged whatever fact the editor's type gives it`() {
        val src = "fun f(row: Row) = \"<b data-text=\\\"\${signal(row.key)}\\\">\${if (row.open) row.key else \"\"}</b>\""
        val both = listOf("\${signal(row.key)}", "\${if (row.open) row.key else \"\"}")
        val told = { type: String -> told(src, mapOf("row.key" to ValueAnswer(type))) }
        assertEquals(both, told("kotlin.String?"))
        assertEquals(both, told("com.acme.Row"))
        assertEquals(both, told("kotlin.collections.List<kotlin.String>"))
        // A number or a literal: the whole is an Any to the editor, and safe whichever it is.
        val any = "if (row.open) row.key else \"\"" to ValueAnswer("kotlin.Any")
        assertEquals(emptyList(), told(src, mapOf("row.key" to ValueAnswer("kotlin.Int"), any)))
        assertEquals(listOf(both[0]), told("$core.Html"))
        for (type in listOf("kotlin.Int?", "kotlin.UInt", "kotlin.Number", "$core.Trusted")) told(type)
    }

    @Test
    fun `a class declared in a class leaves the outer one what it is`() {
        val members = "    fun head(): String = \"<b>x</b>\"\n    fun render() = \"<p>\${head()}</p>\"\n}"
        for (inner in listOf("class Helper", "class Helper { val a = 1 }", "companion object { val A = 1 }", "enum class Kind { A, B }")) {
            assertEquals(listOf("\${head()}"), told("interface Page {\n    $inner\n$members"), inner)
        }
        assertEquals(emptyList(), told("class Card {\n    interface L { fun x() }\n$members"))
        assertEquals(
            listOf("\$body"),
            told(
                "open class Card : Base() {\n    companion object\n    override val body = \"<b>x</b>\"\n    fun r() = \"<p>\$body</p>\"\n}",
            ),
        )
    }

    @Test
    fun `a getter is seen behind its modifiers and a comment`() {
        for (getter in listOf("// adds q\n        get()", "inline get()", "@JvmName(\"t\") get()", "/** Adds. */\n        get()")) {
            assertEquals(
                listOf("\$title"),
                told(
                    "class P(val q: String) {\n    val title: String = \"<b>x</b>\"\n        $getter = field + q\n    fun page() = \"<p>\$title</p>\"\n}",
                ),
                getter,
            )
        }
    }

    @Test
    fun `what is written before a declaration is read however much of it there is, in code alone`() {
        val long = "x".repeat(200)
        val use = "\nfun page() = \"<div>\${a()}\$b</div>\""
        for (between in listOf(" @JvmName(\"c\")", "\n// The badge.\n", " @Suppress(\"$long\")", " @Since(Version(1))")) {
            assertEquals(
                emptyList(),
                told("@Language(\"HTML\")$between fun a(): String = load()\n@Language(\"HTML\")$between val b: String = load()$use"),
                between,
            )
        }
        // A mark in a comment is none.
        assertEquals(listOf("\$b"), told("// @Language(\"HTML\")\nval b: String = load()\nfun page() = \"<div>\$b</div>\""))
        // A mark on a value is none on the declaration after it.
        assertEquals(
            listOf("\$b"),
            told("val a = @Language(\"HTML\") \"x\"\nval b: String = load()\nfun page() = \"<div>\$b</div>\""),
        )
        for (between in listOf("@Since(Version(1))", "@Deprecated(\"$long\")", "/* $long */")) {
            assertEquals(
                listOf("\${head()}", "\$title"),
                told(
                    "abstract class Page {\n    open $between fun head(): String = \"<title>x</title>\"\n" +
                        "    open $between val title = \"<b>x</b>\"\n    fun render() = \"<html>\${head()}\$title</html>\"\n}",
                ),
                between,
            )
        }
    }

    @Test
    fun `a getter is seen however far behind the value it is written`() {
        for (getter in listOf("@Since(Version(1)) get()", "// ${"x".repeat(400)}\n        get()")) {
            assertEquals(
                listOf("\$title"),
                told(
                    "class P(val q: String) {\n    val title: String = \"<b>x</b>\"\n        $getter = field + q\n    fun page() = \"<p>\$title</p>\"\n}",
                ),
                getter,
            )
        }
    }

    @Test
    fun `a getter is seen on the line of its val`() {
        assertEquals(
            listOf("\$title"),
            told(
                "class P(val q: String) {\n    val title: String = \"<b>x</b>\"; get() = field + q\n    fun page() = \"<p>\$title</p>\"\n}",
            ),
        )
    }

    @Test
    fun `a call of a function named get after a val is no getter of it`() {
        assertEquals(
            emptyList(),
            told("fun Route.page() {\n    val badge = \"<b>x</b>\"\n    get(\"/\") { call.respondText(\"<p>\$badge</p>\") }\n}"),
        )
    }

    @Test
    fun `a function's parameters are in sight in the object or the function its expression is`() {
        assertEquals(emptyList(), told("fun f(n: Int) = object : R {\n    fun r() = \"<p>\$n</p>\"\n}"))
        assertEquals(listOf("\$q"), told("fun f(q: String) = object : R {\n    fun r() = \"<p>\$q</p>\"\n}"))
        assertEquals(emptyList(), told("fun f(n: Int): () -> String = fun(): String = \"<p>\$n</p>\""))
        assertEquals(emptyList(), told("fun f(n: Int): () -> String = fun(): String {\n    return \"<p>\$n</p>\"\n}"))
        assertEquals(emptyList(), told("fun f(n: Int) = object : R(n) {\n    fun r() = \"<p>\$n</p>\"\n}"))
        // A declaration after an expression whose line could go on is one of its own.
        for (end in listOf("rows[id]!!", "rows[id] as String?", "rows[id] as List<Int>")) {
            val first = "class Page(val id: String) {\n    fun row(id: Int) = $end\n\n"
            assertEquals(listOf("\$id"), told("$first    fun render() = \"<p>\$id</p>\"\n}"), end)
            assertEquals(listOf("\$id"), told("$first    object O { fun render() = \"<p>\$id</p>\" }\n}"), end)
            assertEquals(listOf("\$id"), told("$first    companion object { fun render() = \"<p>\$id</p>\" }\n}"), end)
            // A receiver in brackets is no parameter list, and its function has a name.
            assertEquals(listOf("\$id"), told("$first    fun (String.() -> Unit).ext() = \"<p>\$id</p>\"\n}"), end)
            assertEquals(listOf("\$id"), told("$first    fun (((Int) -> Unit) -> Unit).ext() = \"<p>\$id</p>\"\n}"), end)
            assertEquals(listOf("\$id"), told("$first    fun ((Int) -> Unit)?.ext() = \"<p>\$id</p>\"\n}"), end)
        }
        // The function after it is one of its own.
        assertEquals(listOf("\$n"), told("fun f(n: Int) = object : R { }\nfun g() = \"<p>\$n</p>\""))
    }

    @Test
    fun `a modifier's word that is a name in a value says nothing of the declaration after it`() {
        val page = "\nval q: String = load()\nfun page() = \"<p>\$q</p>\""
        assertEquals(listOf("\$q"), told("val const = load()\nval c = const$page"))
        assertEquals(listOf("\$q"), told("fun f(door: Door) = door.const$page"))
        val member = "    val title = \"<b>x</b>\"\n    fun render() = \"<p>\$title</p>\"\n}"
        assertEquals(emptyList(), told("class Page(door: Door) {\n    val isOpen = door.open\n$member"))
        // A line that ends in `!!` ends its statement, and the modifier after it is one.
        assertEquals(
            listOf("\$title"),
            told("abstract class Page(q: String?) {\n    val a = q!!\n    open$member".replace("open    val", "open val")),
        )
        assertEquals(
            listOf("\$title"),
            told("abstract class Page {\n    open @[A B] val title = \"<b>x</b>\"\n    fun render() = \"<p>\$title</p>\"\n}"),
        )
    }

    @Test
    fun `a modifier is read on the line of the bracket that closes what stands before it`() {
        val render = "fun render() = \"<p>\$title</p>\""
        assertEquals(listOf("\$title"), told("open class Page { fun a() {} open val title = \"<b>x</b>\"; $render }"))
        val called = "fun render() = \"<p>\${title()}</p>\""
        assertEquals(
            listOf("\${title()}"),
            told("open class Page {\n    fun a() {} open fun title(): String = \"<b>x</b>\"\n    $called\n}"),
        )
        val marked = "@Language(\"HTML\") private val title: String = load()"
        assertEquals(emptyList(), told("object O { init { } $marked; $render }"))
        assertEquals(emptyList(), told("context(Raise<String>) $marked\n$render"))
        val seen = declaredNames("object O { fun a() {} private const val K = \"k\"; class N {} private class H { val x = 1 } }")
        assertEquals(emptySet<String>() to emptySet(), seen.constants to seen.others)
    }

    @Test
    fun `an annotation that begins a function's expression is part of it`() {
        val src = "fun outer(n: String) {\n    fun size(n: Int) = @Suppress(\"X\") pad(n)\n    println(\"<p>\$n</p>\")\n}"
        assertEquals(listOf("\$n"), told(src))
    }

    @Test
    fun `a getter is its val's where the value holds a function with an expression of its own`() {
        val src = "class P(val q: String) {\n    val title = fun() = \"<b>x</b>\"\n        get() = { q }\n}"
        assertEquals(true, declarationOf(src, "title", src.length - 2)?.value?.getter)
    }

    @Test
    fun `a name in backticks is a name whatever it holds, and an annotation left open takes nothing with it`() {
        val page = "    fun other() = \"<b>x</b>\"\n    fun page() = \"<p>\${other()}</p>\"\n}"
        assertEquals(emptyList(), told("class T {\n    fun `reads @get(x`() {}\n$page"))
        assertEquals(setOf("other", "page"), fileFacts("class T {\n    @Suppress(\n$page").functions.keys)
    }

    @Test
    fun `a function without a name left open is read however much follows it`() {
        val src = "class P {\n    fun style(n: Int) = fun(\n" + "    val row1 = \"<b>1</b>\"\n".repeat(400) + "}"
        assertEquals(emptyList(), told(src))
        assertEquals(emptyList(), told("class P {\n    @A<" + "String, ".repeat(9000)))
    }

    @Test
    fun `a modifier is read behind an annotation named in backticks or with type arguments`() {
        val more = listOf("@A<B<C<D>>>", "@A<(Int) -> Unit>", "@A <String>", "@a.`b`", "abstract fun f(): List<String>")
        for (annotation in listOf("@`A`", "@A<String>", "@A<List<String>>") + more) {
            val src = "abstract class P {\n    $annotation open val title = \"<b>x</b>\"\n    fun page() = \"<p>\$title</p>\"\n}"
            assertEquals(listOf("\$title"), told(src), annotation)
        }
    }

    @Test
    fun `a name after a condition is no modifier of the declaration on the next line`() {
        val body = "    if (door.locked) const\n    val title: String = load()\n    println(\"<p>\$title</p>\")"
        assertEquals(listOf("\$title"), told("fun f(door: Door, const: Int) {\n$body\n}"))
        assertEquals(
            listOf("\$title"),
            told("fun f(door: List<Door>, const: Int) {\n${body.replace("if (door.locked)", "for (d in door)")}\n}"),
        )
    }

    @Test
    fun `a loop variable is out of sight after its statement where an annotation stands in the loop`() {
        for (loop in listOf("for (n in @A rows) println(n)", "for (n in rows) @A println(n)")) {
            assertEquals(emptyList(), told("fun f(n: Int, rows: List<String>) {\n    $loop\n    println(\"<p>\$n</p>\")\n}"), loop)
        }
    }

    @Test
    fun `a label after type arguments is no annotation`() {
        val call = "super<A>@C.f(x, { n: String -> \"<p>\$n</p>\" })"
        assertEquals(listOf("\$n"), told("class C(val n: Int) : A() {\n    fun i(x: Int) = $call\n}"))
    }

    @Test
    fun `a bracket that closes nothing ends the value before it`() {
        assertEquals(emptyList(), told("val a = \"<b>a</b>\" }\nfun p() = \"<p>\$a</p>\""))
    }

    @Test
    fun `a type too long to be one is not read, and the file is`() {
        val src = "fun f(): List<" + "x".repeat(8000) + "> = 1\nval a = \"<b>a</b>\"\nfun p() = \"<p>\$a</p>\""
        assertEquals(emptyList(), told(src))
    }

    @Test
    fun `a function named get declared after a val is no getter of it`() {
        val page = "    fun page() = \"<p>\$html</p>\"\n}"
        for (get in listOf("override fun get() = html", "operator fun get(i: Int) = html")) {
            assertEquals(emptyList(), told("class Holder {\n    private val html = \"<b>x</b>\"\n    $get\n$page"), get)
        }
    }

    @Test
    fun `a constructor behind an annotation with arguments declares its parameters`() {
        val src = "class Row @Named(\"x\") constructor(val id: Int, val name: String) {\n    fun page() = \"<p>\$id \$name</p>\"\n}"
        assertEquals(listOf("\$name"), told(src))
    }

    @Test
    fun `a function that returns to a label is not followed`() {
        val f = "fun f(rows: List<Int>): String {\n    rows.forEach { return@forEach }\n    return \"<b>x</b>\"\n}"
        assertEquals(listOf("\${f(rows)}"), told("$f\nfun p(rows: List<Int>) = \"<p>\${f(rows)}</p>\""))
    }

    @Test
    fun `a raw string that closes with four quotes ends the value it is`() {
        val src = "fun re() = Regex(\"\"\"id=\"([^\"]*)\"\"\"\")\nfun bold() = \"<b>x</b>\"\nfun p() = \"<p>\${bold()}</p>\""
        assertEquals(emptyList(), told(src))
    }

    @Test
    fun `a modifier after a block is one with the declaration on the next line, and a long line of words is read`() {
        val page = "    val title = \"<b>x</b>\"\n    fun page() = \"<p>\$title</p>\"\n}"
        assertEquals(listOf("\$title"), told("open class P {\n    fun a() {} open\n$page"))
        assertEquals(emptyList(), told("val a = b open" + " a".repeat(20_000)))
        val seen = declaredNames("class P {\n    fun a() {} private @Suppress(\"a b\") val title = 1\n}")
        assertEquals(emptySet(), seen.others)
        // A word after `=` is a name on whatever line it stands.
        assertEquals(setOf("o", "next"), declaredNames("val o =\n    private\nval next = 1").others)
    }

    @Test
    fun `a private fun interface keeps its members out of other files' sight`() {
        val seen = declaredNames("private fun interface Q {\n    fun run()\n    val title: String get() = \"<b>x</b>\"\n}")
        assertEquals(emptySet(), seen.others)
        // A function half written keeps its modifiers to itself.
        assertEquals(setOf("title"), declaredNames("class P {\n    private fun\n    val title = 1\n}").others)
    }

    @Test
    fun `a modifier after a semicolon is one with the declaration on the next line`() {
        val page = "    val title = \"<b>x</b>\"\n    fun page() = \"<p>\$title</p>\"\n}"
        assertEquals(listOf("\$title"), told("open class P {\n    val a = 1; open\n$page"))
    }

    @Test
    fun `a group of annotations too long to read closes nothing but itself`() {
        val group = "@[A Query(\"\"\"" + "x".repeat(6000) + "\"\"\")]"
        assertEquals(emptySet(), declaredNames("private class P { $group val title = \"<b>x</b>\" }").others)
    }

    @Test
    fun `the literals of a large block are read once each`() {
        // The file is read untimed, each a file of its own so that no reading is kept from the one before; what is
        // timed is finding which of the block's literals are markup.
        var made = 0
        assertGrowsLinearly("the literals of one block", 200, { n: Int ->
            fileFacts(
                "// ${made++}\nimport io.github.markusaugust.streamlord.core.domain.*\nfun f() = interpolate {\n" +
                    (0 until n).joinToString("\n") { "    val a$it = \"x <b>$it</b>\"" } + "\n    \"<p></p>\"\n}",
            )
        }) { it.markupLiterals }
    }

    @Test
    fun `a value whose line ends in a postfix or a type ends there`() {
        for (end in listOf("q!!", "q as String?", "q as List<Int>")) {
            val src = "fun f(q: Any?) {\n    val a = $end\n    val title = \"<b>x</b>\"\n}"
            val a = declarationOf(src, "a", src.length - 2)!!
            assertEquals(end, valueText(src, a.value!!)?.text, end)
        }
        // An arrow, an operator or a comparison carries the line on.
        val src = "fun f(q: Int) {\n    val a = q >\n        1\n}"
        assertEquals("q >\n        1", valueText(src, declarationOf(src, "a", src.length - 2)!!.value!!)?.text)
    }

    @Test
    fun `an expression or a loop's statement that begins with a word ends with its line`() {
        assertEquals(listOf("\$n"), told("fun outer(n: String) {\n    fun size(n: Int) = pad(n)\n    println(\"<p>\$n</p>\")\n}"))
        assertEquals(
            listOf("\$n"),
            told("fun outer(n: String, rows: List<Int>) {\n    for (n in rows) println(n)\n    println(\"<p>\$n</p>\")\n}"),
        )
        // A statement on the next line is still the loop's, and an expression goes on past `if (...)` and `else`.
        assertEquals(
            listOf("\$n"),
            told("fun outer(n: Int, rows: List<String>) {\n    for (n in rows)\n        println(\"<p>\$n</p>\")\n}"),
        )
        assertEquals(emptyList(), told("fun label(n: Int) =\n    if (n > 0)\n        \"a\"\n    else\n        \"<i>\$n</i>\""))
        val inner =
            "fun outer(n: Int) {\n    fun inner(n: String) = if (n.isEmpty())\n        \"none\"\n" +
                "    else\n        \"<p>\$n</p>\"\n}"
        assertEquals(listOf("\$n"), told(inner))
        assertEquals(
            listOf("\$item"),
            told(
                "fun outer(item: Int, items: List<String>) {\n    for (item in\n        items) {\n        println(\"<li>\$item</li>\")\n    }\n}",
            ),
        )
        val loop =
            "fun outer(n: Int, rows: List<String>) {\n    for (n in rows)\n        if (n.isNotEmpty())\n" +
                "            println(\"<li>\$n</li>\")\n}"
        assertEquals(listOf("\$n"), told(loop))
        // A loop's statement ends at `n++`, before an outer `if`'s `else`, and after a do-while.
        for (statement in listOf("count++", "do { println(title) } while (title > 0)")) {
            val src =
                "fun list(title: String, nums: List<Int>) {\n    var count = 0\n    for (title: Int in nums) $statement\n" +
                    "    println(\"<p>\$title</p>\")\n}"
            assertEquals(listOf("\$title"), told(src), statement)
        }
        val pick =
            "fun pick(title: String, a: Boolean, nums: List<Int>) {\n    if (a)\n        for (title: Int in nums) println(title)\n" +
                "    else\n        println(\"<b>\$title</b>\")\n}"
        assertEquals(listOf("\$title"), told(pick))
        // A loop's header that goes on over lines, and an `if` of its statement whose `else` stands on the next line.
        val header =
            "fun list(item: String, items: List<Int>) {\n    for (item: Int in items\n        .filter { it > 0 }) {\n" +
                "        println(\"<li>\$item</li>\")\n    }\n}"
        assertEquals(emptyList(), told(header))
        val branch =
            "fun f(x: String, xs: List<Int>) {\n    for (x: Int in xs)\n        if (x > 0) use(x)\n" +
                "        else println(\"<i>\$x</i>\")\n}"
        assertEquals(emptyList(), told(branch))
        // `catch` and `finally` on the next line, `as?` and a comparison with a number at a line's end carry it on.
        val caught =
            "class A(val n: String) {\n    fun size(n: Int): String = try {\n        n.toString()\n    }\n" +
                "    catch (e: Exception) {\n        \"<p>\$n</p>\"\n    }\n}"
        assertEquals(emptyList(), told(caught))
        for (end in listOf("m as?\n        String", "n<3 && n>\n        1")) {
            val src = "fun f(m: Any, n: Int) {\n    val k = $end\n}"
            assertEquals(end, valueText(src, declarationOf(src, "k", src.length - 2)!!.value!!)?.text, end)
        }
        // An outer `if`'s `else` after a statement whose own `if` has one.
        val closed =
            "fun pick(x: String, a: Boolean, xs: List<Int>) {\n    if (a)\n        for (x: Int in xs) total += if (x > 0) x else 0\n" +
                "    else\n        println(\"<b>\$x</b>\")\n}"
        assertEquals(listOf("\$x"), told(closed))
        // A name that is also an infix function's ends its line; only a keyword carries it on.
        val step = "fun f(step: String) {\n    val current = step\n    val title = \"<h1>x</h1>\"\n}"
        assertEquals("step", valueText(step, declarationOf(step, "current", step.length - 2)!!.value!!)?.text)
        val arrow = "fun f(m: Any) {\n    val h = m as Map<String, () -> Int>\n    val title = \"<b>x</b>\"\n}"
        assertEquals("m as Map<String, () -> Int>", valueText(arrow, declarationOf(arrow, "h", arrow.length - 2)!!.value!!)?.text)
        val compared = "fun f(b: Int, c: Int, d: Int, e: Int) {\n    val a = b < c && d >\n        e\n}"
        assertEquals("b < c && d >\n        e", valueText(compared, declarationOf(compared, "a", compared.length - 2)!!.value!!)?.text)
        val a = "fun f(q: Int) {\n    val a = q>\n        1\n}"
        assertEquals("q>\n        1", valueText(a, declarationOf(a, "a", a.length - 2)!!.value!!)?.text)
    }

    @Test
    fun `a mark reaches what its declaration gives in every layout the text shows`() {
        val page = "\nfun page(name: String) = \"<p>\$name</p>\""
        for (marked in listOf(
            "@Language(\"HTML\") var out = \"Hi <b>\$name</b>\"",
            "@Language(\"HTML\") val html: String get() = \"Hi <b>\$name</b>\"",
            "open class Page { @Language(\"HTML\") open fun head(title: String): String = \"Title <b>\$title</b>\" }",
            "class Page { @Language(\"HTML\") val greeting by lazy { \"Hello <b>\$name</b>\" } }",
            "interface View { @Language(\"HTML\") fun render(): String = \"Hello <b>\$name</b>\" }",
            "@Language(\"HTML\") fun list(items: List<String>): String {\n    items.forEach { if (it.isEmpty()) return@forEach }\n    return \"Items <b>\$name</b>\"\n}",
        )) {
            val src = "import org.intellij.lang.annotations.Language\nval name = load()\n$marked$page"
            val literal = analyzer.lex(src).strings.first { "<b>" in it.text }
            assertEquals(true, isHtmlString(src, literal), marked)
        }
    }

    @Test
    fun `a bracket that closes nothing at a line's end reads no further than a line's worth back`() {
        var made = 0
        assertGrowsLinearly("lines that close nothing", 200, { n: Int ->
            "// ${made++}\n" + "val a = run {\n".repeat(n) + ")\n".repeat(n)
        }) { fileFacts(it) }
    }
}
