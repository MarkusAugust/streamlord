package io.github.markusaugust.streamlord.analysis

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cases of the VS Code extension's `analyze.test.ts`, so the two editors judge the same
 * source the same way.
 */
class AnalyzerTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)
    private val markup = MarkupOptions(requireIds = false, prefix = "data-", checkAttributes = true)

    private fun codes(issues: List<Issue>) = issues.map { it.code }

    private fun expr(text: String) = analyzer.validateExpression(text)

    private fun html(
        src: String,
        opts: MarkupOptions = markup,
    ) = analyzer.validateMarkup(src, opts)

    @Test
    fun `decodes escapes and templates with an offset map`() {
        val src = "x(\"a\\\"b\\n\${'\$'}c\$d\${e}f\")"
        val s = readKotlinStringAt(src, 2)!!
        assertEquals("a\"b\n\$c__kt____kt__f", s.text)
        assertEquals(listOf(InterpolationKind.SIMPLE to "d", InterpolationKind.BRACED to "e"), s.interpolations.map { it.kind to it.text })
        assertEquals("a", src.substring(s.map[0], s.map[0] + 1))
        assertEquals("\\\"", src.substring(s.map[1], s.map[1] + 2))
        assertEquals(src.length - 1, s.end)
    }

    @Test
    fun `reads multi-dollar literals where a single dollar is text`() {
        val src = "f(\$\$\"a\$b\$\$c\$\${e}\${'\$'}\$\$\$d\")"
        val s = readKotlinStringAt(src, 2)!!
        assertEquals(2, s.dollars)
        assertEquals(2, s.start)
        assertEquals("a\$b__kt____kt__\${'\$'}\$__kt__", s.text)
        assertEquals(
            listOf(InterpolationKind.SIMPLE to "c", InterpolationKind.BRACED to "e", InterpolationKind.SIMPLE to "d"),
            s.interpolations.map { it.kind to it.text },
        )
        assertEquals("\$\$c", src.substring(s.interpolations[0].start, s.interpolations[0].end))
        assertEquals("\$\$d", src.substring(s.interpolations[2].start, s.interpolations[2].end))
        val raw = readKotlinStringAt("\$\$\"\"\"<i>\$x</i>\"\"\"", 0)!!
        assertTrue(raw.raw)
        assertEquals("<i>\$x</i>", raw.text)
        assertNull(readKotlinStringAt("\$\$x", 0))
    }

    @Test
    fun `reads raw strings and stops at the last quote of a run`() {
        val src = "f(\"\"\"<div id=\"a\">\"</div>\"\"\"\")"
        val s = readKotlinStringAt(src, 2)!!
        assertTrue(s.raw)
        assertEquals("<div id=\"a\">\"</div>\"", s.text)
        assertEquals(")", src.substring(s.end))
    }

    @Test
    fun `splits positional named string and lambda arguments`() {
        val src =
            """
            stream.patchElements("<li>x</li>", selector = "#list", mode = ElementPatchMode.APPEND) { once = true }
              fun patchElements(x: String) = 1
              dataOn("click", post("/x") + "y")
            """.trimIndent()
        val sites = findCallSites(src, setOf("patchElements", "dataOn", "post"))
        assertEquals(listOf("patchElements", "dataOn", "post"), sites.map { it.name })
        val pe = sites[0]
        assertEquals(
            listOf(null to "<li>x</li>", "selector" to "#list", "mode" to "ElementPatchMode.APPEND"),
            pe.args.map { it.named to (it.string?.text ?: it.text) },
        )
        assertTrue(pe.trailingLambda)
        assertNull(sites[1].args[1].string)
    }

    @Test
    fun `accepts datastar syntax`() {
        assertEquals(emptyList(), expr("\$count++; @post('/x', {headers: {'X-A': '1'}})"))
        assertEquals(emptyList(), expr("evt.key === 'Escape' && (\$open = false)"))
        assertEquals(emptyList(), expr("@peek(() => \$a.b)"))
    }

    @Test
    fun `reads an object literal the way datastar does`() {
        assertEquals(emptyList(), expr("{count: 0, name: '', nested: {a: [1, 2, 3]}}"))
        assertEquals(emptyList(), expr("{count: 0}"))
        assertEquals(listOf(8), expr("{count: }").map { it.start })
        assertEquals(listOf(4), expr("{n: , m: 1}").map { it.start })
        assertEquals(emptyList(), analyzer.analyzeHtml("<div data-signals=\"{count: 0, name: ''}\"></div>", opts))
    }

    @Test
    fun `reports syntax errors with positions and unknown actions`() {
        val bad = expr("@post('/x'")
        assertEquals(Severity.ERROR, bad[0].severity)
        assertEquals(10, bad[0].start)
        assertEquals(listOf("unknown-action"), codes(expr("@Post('/x')")))
        assertContains(expr("@Post('/x')")[0].message, "Did you mean @post")
        assertEquals(listOf("pro-action"), codes(expr("@clipboard('x')")))
        assertEquals(listOf("empty-expression"), codes(expr("  ")))
    }

    @Test
    fun `requires ids on top-level elements without a selector`() {
        assertEquals(listOf("missing-id"), codes(html("<div id=\"a\">x</div><span>y</span>", markup.copy(requireIds = true))))
        assertEquals(emptyList(), html("<span>y</span>"))
    }

    @Test
    fun `finds unclosed and stray tags and top-level text`() {
        assertEquals(listOf("unclosed"), codes(html("<div id=\"a\"><span>x</div>", markup.copy(requireIds = true))))
        assertEquals(listOf("stray-close"), codes(html("</div>")))
        assertEquals(listOf("top-level-text"), codes(html("Hello <b>x</b>")))
        assertEquals(emptyList(), html("<input id=\"i\"><br><script>if (a < b) {}</script>"))
    }

    @Test
    fun `validates data attributes and modifiers`() {
        val src =
            "<div id=\"x\" data-on:click__debounce.500ms.leading__prevent=\"@post('/a')\" data-on-intersect__threshold.150=\"x()\"\n" +
                "      data-on:click__debunce=\"1\" data-on=\"x\" data-text:x=\"1\" data-signal:foo=\"1\" data-bind=\"\$a + 1\" data-persist data-init__delay=\"1\"></div>"
        assertEquals(
            listOf(
                "modifier-args",
                "unknown-modifier",
                "missing-key",
                "unexpected-key",
                "unknown-attribute",
                "signal-name-expected",
                "pro-attribute",
                "modifier-args",
            ),
            codes(html(src)),
        )
        val messages = html(src).map { it.message }
        assertContains(messages.first { "data-signal." in it }, "Did you mean data-signals")
        assertContains(messages.first { "__debunce" in it }, "Did you mean __debounce")
    }

    @Test
    fun `validates expressions inside attribute values but tolerates template syntax`() {
        assertEquals(listOf("expression-syntax"), codes(html("<div id=\"a\" data-text=\"\$x +\"></div>")))
        assertEquals(emptyList(), html("<div id=\"a\" data-text=\"{{ name }}\" data-show=\"{% if x %}1{% endif %}\"></div>"))
    }

    @Test
    fun `warns about capitals in keys which the browser lowercases`() {
        val src =
            "<div data-signals:fooBar=\"1\" data-computed:fullName=\"1\" data-bind:MySignal data-on:widgetLoaded=\"x()\" " +
                "data-class:isOpen=\"\$open\" data-attr:ariaLabel=\"'x'\" data-style:backgroundColor=\"'red'\"></div>"
        val issues = analyzer.analyzeHtml(src, opts)
        assertEquals(List(7) { "key-case" }, codes(issues))
        assertEquals(
            listOf(
                "foo-bar",
                "full-name",
                "my-signal__case.pascal",
                "widget-loaded__case.camel",
                "is-open__case.camel",
                "aria-label",
                "background-color",
            ),
            issues.map { it.fixes[0].text },
        )
        assertContains(issues[0].message, "\$foobar")
        assertEquals("fooBar", src.substring(issues[0].start, issues[0].end))
        val fine =
            "<div data-signals:foo-bar=\"1\" data-on:widget-loaded__case.camel=\"x()\" data-bind=\"fooBar\" data-signals=\"{fooBar: 1}\" " +
                "data-class:hover:bg-red-500=\"\$x\"></div>"
        assertEquals(emptyList(), analyzer.analyzeHtml(fine, opts))
        val explicit =
            analyzer.analyzeHtml(
                "<div data-signals:fooBar__case.kebab=\"1\" data-on:widgetLoaded__case.camel=\"x()\"></div>",
                opts,
            )
        assertEquals(listOf("key-case", "key-case"), codes(explicit))
        assertEquals(listOf("foo-bar", "widget-loaded"), explicit.map { it.fixes[0].text })
        assertContains(explicit[0].message, "foo-bar__case.kebab")
        val custom = analyzer.analyzeHtml("<div data-style:--myColor=\"\$c\"></div>", opts)
        assertEquals(listOf("--my-color"), custom.map { it.fixes[0].text })
        assertEquals(
            listOf("key-case"),
            codes(analyzer.analyzeHtml("<div data-star-signals:fooBar=\"1\"></div>", AnalyzeOptions("data-star-", true))),
        )
    }

    @Test
    fun `hints what the dsl writes on the wire for a camelCase key`() {
        val src =
            """
            div {
              dataSignals("fooBar", "1")
              dataOn("widgetLoaded", "x()") { once = true }
              dataClass("isOpen", "$" + "open")
              dataAttr("ariaLabel", "'x'")
              dataSignals("MySignal", "1")
              dataSignals("fooBar", "1", case = Case.KEBAB)
              dataOn("customEvent", "x()") { case = Case.KEBAB }
              dataSignals("foo-bar", "1")
              dataSignals("{fooBar: 1}")
              dataBind("fooBar")
              dataOnClick("@post('/Add')", { once = true })
              dataOnFetch("evt.detail.type === 'Started'") { window = true }
              dataAttr("viewBox", "${'$'}box")
              dataRef("myRef", Case.CAMEL)
              dataIndicator("isLoading", Case.CAMEL)
              dataSignals("{fooBar: 1}", Case.CAMEL)
              dataPersist("myKey")
              dataMatchMedia("isMobile", mediaQuery = "(max-width: 600px)")
            }
            """.trimIndent()
        val hints = analyzer.analyzeKotlin(src, opts).filter { it.code == "key-case-wire" }
        assertTrue(hints.all { it.severity == Severity.HINT })
        assertEquals(
            listOf(
                "\"fooBar\"",
                "\"widgetLoaded\"",
                "\"isOpen\"",
                "\"ariaLabel\"",
                "\"MySignal\"",
                "\"fooBar\"",
                "\"customEvent\"",
                "\"viewBox\"",
                "\"myRef\"",
                "\"isLoading\"",
                "\"myKey\"",
                "\"isMobile\"",
            ),
            hints.map { src.substring(it.start, it.end) },
        )
        assertEquals(
            listOf(
                "data-signals:foo-bar",
                "data-on:widget-loaded__case.camel",
                "data-class:is-open__case.camel",
                "data-attr:aria-label",
                "data-signals:my-signal__case.pascal",
                "data-signals:foo-bar__case.kebab",
                "data-on:custom-event__case.kebab",
                "data-attr:view-box",
                "data-ref:my-ref__case.camel",
                "data-indicator:is-loading__case.camel",
                "data-persist:my-key",
                "data-match-media:is-mobile",
            ),
            hints.map { Regex("""as (data-[a-z-]+:[^,]+),""").find(it.message)!!.groupValues[1] },
        )
        assertContains(hints[0].message, "\$fooBar")
        assertContains(hints[7].message, "data-attr=\"{viewBox: ...}\"")
    }

    @Test
    fun `warns not errors when the template is only part of an expression`() {
        val src =
            "fun seed(initial: Int) = \"\"\"<div data-signals=\"{count: \$initial}\" data-signals:count=\"\$initial\" " +
                "data-text=\"\$initial\"></div>\"\"\""
        val issues = analyzer.analyzeKotlin(src, opts).filter { it.code == "kotlin-interpolation" }
        assertEquals(listOf(Severity.WARNING, Severity.WARNING, Severity.WARNING), issues.map { it.severity })
        assertContains(issues[0].message, "server value")
        assertEquals(
            listOf("Make it a \$\$ literal, where \$initial is a signal", "Escape as \${'\$'}initial"),
            issues[0].fixes.map { it.title },
        )
    }

    @Test
    fun `does not read a parameter annotation with a default as a marker for the next string`() {
        val src = "fun render(@Language(\"HTML\") html: String? = null, title: String = \"Untitled \$x\") = title"
        assertEquals(emptyList(), analyzer.analyzeKotlin(src, opts))
        assertEquals(
            listOf("<b data-onn:x=\"1\">"),
            findKotlinStrings("val c = '\\u0041'; val s = \"<b data-onn:x=\\\"1\\\">\"").map { it.text },
        )
    }

    @Test
    fun `treats interpolation in an aliased-prefix expression as the same error`() {
        val src = "fun f(count: Int) = \"\"\"<p data-star-text=\"\$count\"></p>\"\"\""
        val aliased = AnalyzeOptions("data-star-", true)
        assertEquals(Severity.WARNING, analyzer.analyzeKotlin(src, aliased).first { it.code == "kotlin-interpolation" }.severity)
        assertEquals(Severity.HINT, analyzer.analyzeKotlin(src, opts).first { it.code == "kotlin-interpolation" }.severity)
    }

    @Test
    fun `warns about a hyphen after a signal which datastar swallows into the name`() {
        val issues = expr("\$foo-bar + \$form.first-name-x + \$a - \$b + \$count-1")
        assertEquals(listOf("signal-kebab", "signal-kebab", "signal-kebab"), codes(issues))
        assertEquals(listOf("\$fooBar", "\$form.firstNameX", "\$count - 1"), issues.map { it.fixes[0].text })
        assertEquals(listOf("Change to \$fooBar", "Write \$foo - bar"), issues[0].fixes.map { it.title })
        assertContains(issues[2].message, "one signal named count-1")
        assertEquals(emptyList(), expr("\$fooBar && \$a-\$b && \$n - 1"))
        // `$total-el.offsetWidth` is `$['total-el']['offsetWidth']` to Datastar: the subtraction the author meant needs spaces.
        val scope = expr("(\$total-el.offsetWidth) + 'px'; \$count-evt.detail.delta; \$n-fn(1); \$m-arr[0]")
        assertEquals(4, scope.size)
        assertEquals(listOf("\$totalEl.offsetWidth", "\$total - el.offsetWidth"), scope[0].fixes.map { it.text })
        assertEquals(emptyList(), expr("@get('/item/\$id-preview') && \$x"))
        assertEquals(listOf("signal-kebab"), codes(analyzer.analyzeKotlin("dataText(\"\${'\$'}foo-bar\")", opts)))
    }

    @Test
    fun `honours the aliased prefix`() {
        assertEquals(emptyList(), html("<div data-star-on:click=\"x()\" data-on:click=\"y(\"></div>", markup.copy(prefix = "data-star-")))
        assertEquals(listOf("prefix-mismatch"), codes(html("<div data-star-on:click=\"x()\"></div>")))
    }

    @Test
    fun `flags kotlin interpolation of signals and maps syntax errors to source`() {
        val src =
            "div {\n  dataOnClick(\"\$count++\")\n  dataText(\"\${'\$'}count +\")\n" +
                "  dataOn(\"keydown\", \"evt.key === 'x' && (\\\$open = false)\") { once = true }\n}"
        val issues = analyzer.analyzeKotlin(src, opts)
        assertEquals(listOf("kotlin-interpolation", "expression-syntax"), codes(issues))
        assertEquals("\$count", src.substring(issues[0].start, issues[0].end))
        assertContains(issues[0].message, "signal(\"count\")")
        assertTrue(issues[1].start > src.indexOf("dataText"))
    }

    @Test
    fun `checks html patches for ids selectors and modes`() {
        val src =
            """
            patchElements(""${'"'}<div>no id</div>""${'"'})
            patchElements("<li>x</li>", selector = "#list", mode = ElementPatchMode.APPEND)
            patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
            removeElements(" ")
            executeScript("var s = '</script>'")
            respondElements(elements { div { id = "x" } })
            """.trimIndent()
        assertEquals(
            listOf("missing-id", "mode-needs-selector", "blank-selector", "script-close"),
            codes(analyzer.analyzeKotlin(src, opts)),
        )
    }

    @Test
    fun `keeps scanning after raw strings nested comments and char literals`() {
        val src =
            """
            s.patchElements(""${'"'}<div>a</div>""${'"'})
            s.patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
            val c = '"'
            /* outer /* inner */ still comment: patchElements("<p>") */
            s.patchElements(""${'"'}<div id="a"><span>x</div>""${'"'})
            s.removeElements(" ")
            """.trimIndent()
        assertEquals(listOf("missing-id", "mode-needs-selector", "unclosed", "blank-selector"), codes(analyzer.analyzeKotlin(src, opts)))
    }

    @Test
    fun `ignores declarations comments and dsl-built html`() {
        val src =
            """
            // dataOnClick("${'$'}x")
            /* patchElements("<div>") */
            fun dataOnClick(expression: String) = 1
            patchElements(selector = "#a") { li { +"x" } }
            """.trimIndent()
        assertEquals(emptyList(), analyzer.analyzeKotlin(src, opts))
    }

    @Test
    fun `treats raw strings and dotted signals`() {
        assertEquals(emptyList(), analyzer.analyzeKotlin("dataEffect(\"\"\"\n  \${'\$'}user.name = \"x\"\n\"\"\")", opts))
    }

    private val free =
        "@Language(\"HTML\")\n" +
            "fun side(tilstand: Tilstand): String = \"\"\"\n" +
            "  <div data-signals=\"{count: 0}\" data-on:click__debunce.500ms=\"@post('/x')\">\n" +
            "    <span data-text=\"\${'\$'}count\"></span>\n" +
            "    <p data-text=\"\$navn\"></p>\n" +
            "    <i>\$title</i>\n" +
            "  </div>\n" +
            "\"\"\"\n" +
            "val row = \"<li data-onn:click=\\\"x()\\\">x</li>\"\n" +
            "val notHtml = \"count: \$count\"\n" +
            "val sql = \"\"\"select * from x where a < 1 and b = \$b\"\"\"\n" +
            "s.patchElements(\"\"\"<div data-onn:x=\"1\"></div>\"\"\")\n" +
            "dataText(\"\$count\")"

    @Test
    fun `checks free html strings like a template and interpolation inside expressions as a warning`() {
        val issues = analyzer.analyzeKotlin(free, opts)
        assertEquals(
            listOf(
                "unknown-attribute",
                "missing-id",
                "kotlin-interpolation",
                "kotlin-interpolation",
                "kotlin-interpolation",
                "unknown-modifier",
                "unknown-attribute",
            ),
            codes(issues),
        )
        val (inExpression, inText) = issues.filter { it.code == "kotlin-interpolation" && free.substring(it.start, it.end) != "\$count" }
        assertEquals("\$navn", free.substring(inExpression.start, inExpression.end))
        assertEquals(Severity.WARNING, inExpression.severity)
        assertEquals(
            listOf("Make it a \$\$ literal, where \$navn is a signal", "Escape as \${'\$'}navn"),
            inExpression.fixes.map { it.title },
        )
        assertEquals(Severity.HINT, inText.severity)
        assertEquals(emptyList(), inText.fixes)
        assertEquals("\$title", free.substring(inText.start, inText.end))
    }

    @Test
    fun `finds html strings by content marker or call site`() {
        fun at(needle: String) = analyzer.htmlStringAt(free, free.indexOf(needle) + 1)
        assertEquals(true, at("data-signals")?.raw)
        assertEquals(false, at("data-onn:click")?.raw)
        assertNull(at("count: \$count"))
        assertNull(at("select *"))
        assertEquals(true, at("data-onn:x")?.raw)
        assertNull(at("\"\$count\")"))
        assertNull(analyzer.htmlStringAt(free, free.indexOf("fun side")))
    }

    @Test
    fun `trusts multi-dollar literals but checks everything else`() {
        val freeMd = "fun side() = \$\$\"\"\"<div data-text=\"\$count\" data-signals=\"{n: \$\$n}\" data-onn:x=\"1\"></div>\"\"\""
        assertEquals(listOf("unknown-attribute"), codes(analyzer.analyzeKotlin(freeMd, opts)))
        val call = "s.patchElements(\$\$\"\"\"<div id=\"a\" data-text=\"\$count +\"></div>\"\"\")"
        val syntax = analyzer.analyzeKotlin(call, opts).single()
        assertEquals("expression-syntax", syntax.code)
        assertTrue(call.substring(syntax.start, syntax.end).isNotEmpty())
        assertTrue(syntax.start > call.indexOf("\$count"))
        assertEquals(
            emptyList(),
            codes(analyzer.analyzeKotlin("dataOnClick(\$\$\"\$count++\"); dataOn(\"keydown\", \$\$\"\$open = !\$open\")", opts)),
        )
        assertEquals(listOf("expression-syntax"), codes(analyzer.analyzeKotlin("dataOnClick(\$\$\"\$count +\")", opts)))
        assertEquals(
            listOf(true, true),
            findCallSites("s.patchElements(\$\$\"\"\"<b></b>\"\"\", selector = \"#x\")", setOf("patchElements"))[0].args.map {
                it.string !=
                    null
            },
        )
    }

    @Test
    fun `honours the injection marker without a leading tag`() {
        val marked = "// language=HTML\nval fragment = \"\"\"Hei <b data-onn:y=\"1\">du</b>\"\"\""
        assertEquals(listOf("unknown-attribute"), codes(analyzer.analyzeKotlin(marked, opts)))
        assertEquals(emptyList(), codes(analyzer.analyzeKotlin(marked.replace("// language=HTML\n", ""), opts)))
        val annotated = "@Language(\"html\")\nprivate val head = \"\"\"\n  \${'\$'}{title}<title data-text=\"\$t\"></title>\n\"\"\""
        assertEquals(listOf("kotlin-interpolation"), codes(analyzer.analyzeKotlin(annotated, opts)))
        val param = "fun wrap(@Language(\"HTML\") html: String) = \"a <b data-onn:x='1'>\" + html"
        assertEquals(emptyList(), analyzer.analyzeKotlin(param, opts))
        val onFunction = "@Language(\"HTML\")\nfun greeting(name: String): String = \"\"\"Hello <b data-onn:click=\"x()\">\$name</b>\"\"\""
        assertEquals(listOf("kotlin-interpolation", "unknown-attribute"), codes(analyzer.analyzeKotlin(onFunction, opts)))
    }

    @Test
    fun `validates a template`() {
        val src =
            "<form data-on:submit__prevent=\"@post('/save')\" data-indicator=\"busy\"><input data-bind:search data-onn:x=\"1\">" + "</form>"
        assertEquals(listOf("unknown-attribute"), codes(analyzer.analyzeHtml(src, opts)))
    }

    private fun fixture(name: String) = codes(analyzer.analyzeHtml(File("../editors/vscode/test/fixtures/$name").readText(), opts))

    @Test
    fun `matches the expectations written in the jvm template fixtures`() {
        assertEquals(listOf("unknown-modifier", "expression-syntax"), fixture("template.jte"))
        assertEquals(listOf("unknown-attribute", "missing-key"), fixture("template.ftl"))
        assertEquals(listOf("modifier-args"), fixture("template.vm"))
        assertEquals(listOf("pro-attribute", "expression-syntax"), fixture("template.mustache"))
        assertEquals(
            listOf(
                "unknown-modifier",
                "modifier-args",
                "missing-key",
                "unknown-attribute",
                "expression-syntax",
                "pro-attribute",
                "pro-attribute",
                "prefix-mismatch",
            ),
            fixture("template.html"),
        )
    }

    @Test
    fun `only treats a template keyword as such at the start of a value or after whitespace`() {
        assertEquals(
            listOf("expression-syntax", "expression-syntax"),
            codes(analyzer.analyzeHtml("<a data-on:click=\"@get('/docs#include') +\" data-text=\"'#end' +\"></a>", opts)),
        )
        assertEquals(emptyList(), analyzer.analyzeHtml("<a data-on:click=\"#if(\$a) x() #end\" data-text=\"@if(x) 1 @endif\"></a>", opts))
    }

    @Test
    fun `skips template tags and comments when checking completeness`() {
        val ftl = "<#if x><div id=\"a\" data-text=\"\${y}\"></div><#else><p id=\"b\">no</p></#if><#-- <span> --><%-- <b> --%>"
        assertEquals(emptyList(), html(ftl, markup.copy(requireIds = true)))
        assertEquals(emptyList(), html("@if(x)<div id=\"a\"></div>@endif", markup.copy(requireIds = true)))
    }

    @Test
    fun `collects signals from kotlin and html`() {
        val kt =
            "dataSignals(\"count\" to 0, \"user\" to mapOf(\"name\" to \"\")); dataBind(\"search\"); signal(\"open\"); " +
                "patchSignals(Search(q = \"x\"))\n" +
                "      mapOf(\"notASignal\" to 1); removeSignals(\"gone\", \"away\")\n" +
                "      @Serializable data class Search(val query: String = \"\", val page: Int = 1)"
        assertEquals(
            listOf("away", "count", "gone", "name", "open", "page", "query", "search", "user"),
            collectSignals(kt, SourceLanguage.KOTLIN).sorted(),
        )
        val h = "<div data-signals=\"{count: 1, open: false}\" data-bind:first-name data-text=\"\$other.x\"></div>"
        assertEquals(listOf("count", "firstName", "open", "other.x"), collectSignals(h, SourceLanguage.HTML).sorted())
        val raw =
            "fun side() = \"\"\"<div data-signals=\"{draft: '', __step: 1, label: 'a__b', count: 0}\" data-bind:search " +
                "data-indicator=\"busy\" " +
                "data-text=\"\$kotlinTemplate\" data-signals:foo-bar__ifmissing=\"1\" data-computed:total__case.camel=\"1\"></div>\"\"\""
        assertEquals(
            listOf("__step", "busy", "count", "draft", "fooBar", "label", "search", "total"),
            collectSignals(raw, SourceLanguage.KOTLIN).sorted(),
        )
    }

    @Test
    fun `collects ids and classes from kotlin dsl and html`() {
        val src =
            "div { id = \"feed\"; classes = setOf(\"card\", \"dark\") }\n" +
                "      patchElements(\"\"\"<ul id=\"list\" class=\"menu open\"><li id=\"\${item.id}\" class=\"\$cls\"></li></ul>\"\"\")\n" +
                "      <span id=\"counter\" class=\"big\"></span>"
        val s = collectSelectors(src)
        assertEquals(listOf("counter", "feed", "list"), s.ids.sorted())
        assertEquals(listOf("big", "card", "dark", "menu", "open"), s.classes.sorted())
    }

    @Test
    fun `documents attributes and actions`() {
        val on = analyzer.catalog.attributesByName["on"]!!
        val doc = attributeDoc(on)
        assertContains(doc, "**data-on**")
        assertContains(doc, "__debounce.500ms")
        assertContains(doc, "**Key casing:**")
        assertContains(actionDoc(analyzer.catalog.actionsByName["post"]!!), "@post(uri, options?)")
        assertContains(markdownToHtml("**a** `b` _c_"), "<b>a</b> <code>b</code> <i>c</i>")
        assertEquals("<p><code>__once</code>, <code>__exit</code> <i>Pro</i></p>", markdownToHtml("`__once`, `__exit` _Pro_"))
        assertFalse(
            analyzer.catalog.attributesByName["persist"]!!
                .let { attributeDoc(it) }
                .contains("**Key casing:**")
                .not(),
        )
        assertNotNull(analyzer.catalog.parseAttributeName("data-on:click__debounce.500ms", "data-"))
    }
}

class TokensTest {
    @Test
    fun `tokenizes datastar expressions`() {
        val text = "\$count++; @post('/x', {headers: {'X-A': \$token}}); el.value; /a\\/b/g.test(evt.key) && \${'\$'}open"
        val tokens = tokenizeExpression(text).map { text.substring(it.start, it.end) to it.kind }
        assertEquals(
            listOf(
                "\$count" to TokenKind.SIGNAL,
                "@post" to TokenKind.ACTION_BACKEND,
                "'/x'" to TokenKind.STRING,
                "headers" to TokenKind.OPTION_KEY,
                "'X-A'" to TokenKind.STRING,
                "\$token" to TokenKind.SIGNAL,
                "el" to TokenKind.SCOPE_VARIABLE,
                "/a\\/b/g" to TokenKind.REGEX,
                "evt" to TokenKind.SCOPE_VARIABLE,
                "\${'\$'}" to TokenKind.KOTLIN_DOLLAR_ESCAPE,
                "open" to TokenKind.SIGNAL,
            ),
            tokens,
        )
        assertEquals(
            listOf(
                "\$user" to TokenKind.SIGNAL,
                ".name.first" to TokenKind.SIGNAL_PATH,
                "500ms" to TokenKind.DURATION,
                "3" to TokenKind.NUMBER,
            ),
            tokenizeExpression("\$user.name.first + 500ms + 3").let { t ->
                t.map {
                    "\$user.name.first + 500ms + 3".substring(it.start, it.end) to
                        it.kind
                }
            },
        )
        assertEquals(listOf(TokenKind.NUMBER, TokenKind.KEYWORD), tokenizeExpression("x = a ? 1 : typeof b").map { it.kind })
    }

    @Test
    fun `splits attribute names into parts`() {
        val name = "data-on:click__debounce.500ms.leading__once"
        val parts = tokenizeAttributeName(name, "data-")!!.map { name.substring(it.start, it.end) to it.part }
        assertEquals(
            listOf(
                "data-" to NamePart.PREFIX,
                "on" to NamePart.PLUGIN,
                ":" to NamePart.KEY_SEPARATOR,
                "click" to NamePart.KEY,
                "__" to NamePart.MODIFIER_SIGIL,
                "debounce" to NamePart.MODIFIER,
                "." to NamePart.MODIFIER_ARG_DOT,
                "500ms" to NamePart.MODIFIER_ARG,
                "." to NamePart.MODIFIER_ARG_DOT,
                "leading" to NamePart.MODIFIER_ARG,
                "__" to NamePart.MODIFIER_SIGIL,
                "once" to NamePart.MODIFIER,
            ),
            parts,
        )
        assertNull(tokenizeAttributeName("class", "data-"))
        assertEquals(listOf(NamePart.PREFIX, NamePart.PLUGIN), tokenizeAttributeName("data-text", "data-")!!.map { it.part })
    }
}
