package io.github.markusaugust.streamlord.html

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.port.driven.BufferedSseSink
import kotlinx.coroutines.test.runTest
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HtmlDslTest {
    @Test
    fun `elements renders compact html`() {
        assertEquals(
            """<div id="a"><span>x</span></div>""",
            elements {
                div {
                    id = "a"
                    span { +"x" }
                }
            },
        )
    }

    @Test
    fun `patchElements from the dsl goes straight to the stream`() =
        runTest {
            val sink = BufferedSseSink()
            Streamlord.Default.stream(sink).patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
                li { +"one" }
                li { +"two" }
            }
            assertEquals(
                "event: datastar-patch-elements\ndata: selector #feed\ndata: mode append\ndata: elements <li>one</li><li>two</li>\n\n",
                sink.text(),
            )
        }

    @Test
    fun `signals attributes`() {
        val html =
            elements {
                div {
                    dataSignals("count" to 0, "user" to mapOf("name" to "Gorvek"), ifMissing = true)
                    dataSignals("open", "false", case = Case.KEBAB)
                    dataComputed("double", "${'$'}count * 2")
                    dataJsonSignals(SignalFilter.include("^user"), terse = true)
                }
            }
        assertEquals(
            """<div data-signals__ifmissing="{&quot;count&quot;:0,&quot;user&quot;:{&quot;name&quot;:&quot;Gorvek&quot;}}" """ +
                """data-signals:open__case.kebab="false" data-computed:double="${'$'}count * 2" """ +
                """data-json-signals__terse="{include: /^user/}"></div>""",
            html,
        )
    }

    @Test
    fun `event attributes with modifiers`() {
        val html =
            elements {
                button {
                    dataOnClick(post("/add")) {
                        debounce = 300.milliseconds
                        debounceLeading = true
                        prevent = true
                        viewTransition = true
                    }
                    dataOn("keydown", "evt.key === 'Escape' && (${set("open", false)})") {
                        window = true
                        once = true
                    }
                    dataOnInterval(get("/tick")) {
                        duration = 2.seconds
                        leading = true
                    }
                    dataOnIntersect(get("/more")) {
                        once = true
                        threshold = 50
                    }
                    dataOnSignalPatch("console.log(patch)", SignalFilter.exclude("^_")) { throttle = 1.seconds }
                    dataIndicator("busy")
                }
            }
        assertEquals(
            """<button data-on:click__debounce.300ms.leading__viewtransition__prevent="@post('/add')" """ +
                """data-on:keydown__once__window="evt.key === 'Escape' &amp;&amp; (${'$'}open = false)" """ +
                """data-on-interval__duration.2000ms.leading="@get('/tick')" """ +
                """data-on-intersect__once__threshold.50="@get('/more')" """ +
                """data-on-signal-patch__throttle.1000ms="console.log(patch)" data-on-signal-patch-filter="{exclude: /^_/}" """ +
                """data-indicator="busy"></button>""",
            html,
        )
    }

    @Test
    fun `binding display and morph attributes`() {
        val html =
            elements {
                input {
                    dataBind("search") {
                        case = Case.CAMEL
                        events = listOf("input", "blur")
                    }
                    dataRef("box")
                    dataText(signal("search"))
                    dataShow(not("hidden"))
                    dataClass("active", signal("on"))
                    dataStyle("color", "'red'")
                    dataAttr("disabled", signal("busy"))
                    dataIgnore(self = true)
                    dataIgnoreMorph()
                    dataPreserveAttr("open", "class")
                    dataInit(set("count", 1)) { delay = 500.milliseconds }
                    dataEffect(increment("count"))
                }
            }
        assertEquals(
            """<input data-bind:search__case.camel__event.input.blur="" data-ref="box" data-text="${'$'}search" """ +
                """data-show="!${'$'}hidden" data-class:active="${'$'}on" data-style:color="'red'" data-attr:disabled="${'$'}busy" """ +
                """data-ignore__self="" data-ignore-morph="" data-preserve-attr="open class" """ +
                """data-init__delay.500ms="${'$'}count = 1" data-effect="${'$'}count++">""",
            html,
        )
    }

    @Test
    fun `actions render options only when set and quote uris safely`() {
        assertEquals("""@get('/a')""", get("/a"))
        assertEquals("""@delete('/x?y=\'1\'')""", delete("/x?y='1'"))
        assertEquals("""@query('/search')""", query("/search"))
        assertEquals(
            """@post('/form', {contentType: 'form', filterSignals: {include: /^form\//}, selector: '#f', headers: {'X-Csrf-Token': 't'}, openWhenHidden: true, retry: 'never', retryInterval: 500, requestCancellation: 'cleanup'})""",
            post("/form") {
                contentType = FetchOptions.ContentType.FORM
                filterSignals = SignalFilter.include("^form/")
                selector = "#f"
                headers = mapOf("X-Csrf-Token" to "t")
                openWhenHidden = true
                retry = FetchOptions.Retry.NEVER
                retryInterval = 500.milliseconds
                requestCancellation = FetchOptions.RequestCancellation.CLEANUP
            },
        )
        assertEquals("@setAll(false, {include: /^menu\\./})", setAll(false, SignalFilter.include("^menu\\.")))
        assertEquals("@setAll('x', {include: /.*/, exclude: /_temp$/})", setAll("x", SignalFilter.exclude("_temp$")))
        assertEquals("@toggleAll()", toggleAll())
        assertEquals("@toggleAll({include: /.*/, exclude: /^is/})", toggleAll(SignalFilter.exclude("^is")))
        assertEquals("@peek(() => ${'$'}count)", peek(signal("count")))
    }

    @Test
    fun `payload and response overrides`() {
        assertEquals(
            """@post('/x', {payload: {id: 7}, responseOverrides: {selector: '#out', mode: 'inner', useViewTransition: true}})""",
            post("/x") {
                payload = mapOf("id" to 7)
                responseOverrides {
                    selector = "#out"
                    mode = ElementPatchMode.INNER
                    useViewTransition = true
                }
            },
        )
        assertEquals(
            """@get('/x', {payload: {id: ${'$'}selected}, responseOverrides: {onlyIfMissing: true}})""",
            get("/x") {
                payloadExpr = "{id: ${'$'}selected}"
                responseOverrides { onlyIfMissing = true }
            },
        )
    }

    @Test
    fun `fetch lifecycle sugar`() {
        assertEquals(
            """<div data-on:datastar-fetch__window="evt.detail.type === 'started' &amp;&amp; (${'$'}busy = true)"></div>""",
            elements { div { dataOnFetch("evt.detail.type === '${FetchEventType.STARTED}' && (${set("busy", true)})") { window = true } } },
        )
    }

    @Test
    fun `aliased bundle prefix applies everywhere`() {
        DatastarAttributes.prefix = "data-star-"
        try {
            assertEquals(
                """<div data-star-signals="{&quot;n&quot;:1}" data-star-on:click__once="x()" data-star-ignore__self="" data-star-json-signals=""></div>""",
                elements {
                    div {
                        dataSignals("n" to 1)
                        dataOnClick("x()") { once = true }
                        dataIgnore(self = true)
                        dataJsonSignals()
                    }
                },
            )
        } finally {
            DatastarAttributes.prefix = "data-"
        }
    }

    @Test
    fun `remaining event sugar, nonce, verbs and responses`() {
        val html =
            elements {
                html {
                    dataNonce("n0nce")
                    form {
                        dataOnSubmit(post("/save"))
                        input {
                            dataOnInput(put("/draft"))
                            dataOnChange(patch("/field"))
                            dataOnKeydown("evt.key")
                        }
                    }
                }
            }
        assertEquals(
            """<html data-nonce="n0nce"><form data-on:submit="@post('/save')">""" +
                """<input data-on:input="@put('/draft')" data-on:change="@patch('/field')" data-on:keydown="evt.key"></form></html>""",
            html,
        )
        assertEquals("""'a\'b'""", js("a'b"))
        val response = elementsResponse(selector = "#out", mode = ElementPatchMode.INNER) { span { +"x" } }
        assertEquals("<span>x</span>", response.body)
        assertEquals(mapOf("datastar-selector" to "#out", "datastar-mode" to "inner"), response.headers)
    }

    @Test
    fun `expression helpers`() {
        assertEquals(
            "${'$'}user.name = 'Gorvek'; ${'$'}open = !${'$'}open; ${'$'}n--",
            statements(set("user.name", "Gorvek"), toggle("open"), decrement("n")),
        )
        assertEquals("${'$'}a = ${'$'}b + 1", setExpr("a", "${'$'}b + 1"))
    }

    /**
     * The browser lowercases attribute names and Datastar camel-cases signal keys, so the name
     * you write must survive both. The DSL writes camelCase keys as kebab-case, which Datastar
     * turns back into the same camelCase; helpers read kebab-case keys the way Datastar does.
     */
    @Test
    fun `camelCase names survive the attribute key`() {
        val html =
            elements {
                div {
                    dataSignals("fooBar", "1")
                    dataSignals("form.firstName", "''", ifMissing = true)
                    dataComputed("fullName", "${'$'}first + ${'$'}last")
                    dataOn("widgetLoaded", "x()") { once = true }
                    dataClass("isOpen", "${'$'}open")
                    dataStyle("backgroundColor", "'red'")
                    dataAttr("ariaLabel", "'x'")
                }
            }
        assertEquals(
            """<div data-signals:foo-bar="1" data-signals:form.first-name__ifmissing="''" """ +
                """data-computed:full-name="${'$'}first + ${'$'}last" """ +
                """data-on:widget-loaded__once__case.camel="x()" data-class:is-open__case.camel="${'$'}open" """ +
                """data-style:background-color="'red'" data-attr:aria-label="'x'"></div>""",
            html,
        )
        // Already kebab, snake or lower-case: written as given. An explicit case is never second-guessed.
        assertEquals(
            """<div data-signals:foo-bar="1" data-signals:foo_bar="2" data-on:my-event="x()" data-signals:foo-bar__case.kebab="3"></div>""",
            elements {
                div {
                    dataSignals("foo-bar", "1")
                    dataSignals("foo_bar", "2")
                    dataOn("my-event", "x()")
                    dataSignals("fooBar", "3", case = Case.KEBAB)
                }
            },
        )
        // A leading capital needs __case.pascal to come back as written.
        assertEquals(
            """<div data-signals:my-signal__case.pascal="1" data-on:my-event__case.pascal="x()"></div>""",
            elements {
                div {
                    dataSignals("MySignal", "1")
                    dataOn("MyEvent", "x()")
                }
            },
        )
        // Capitals that Datastar's own kebab would merge (myURL) still come back exactly.
        assertEquals(
            """<div data-signals:my-u-r-l="1" data-signals:item2-name="2"></div>""",
            elements {
                div {
                    dataSignals("myURL", "1")
                    dataSignals("item2Name", "2")
                }
            },
        )
        assertEquals("myURL", Casing.camel(Casing.kebab("myURL")))
        assertEquals("item2Name", Casing.camel(Casing.kebab("item2Name")))
        // Helpers read a kebab key the way Datastar names the signal.
        assertEquals("${'$'}fooBar", signal("foo-bar"))
        assertEquals("${'$'}form.firstName++", increment("form.first-name"))
        assertEquals("${'$'}fooBar", signal("fooBar"))
        assertFailsWith<InvalidSignalNameException> { signal("") }
        assertFailsWith<InvalidSignalNameException> { signal("my signal") }
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("a b", "1") } } }
        // Index and bracket references, Tailwind variants and namespaced attributes are keys and names too.
        assertEquals("${'$'}items[0].name", signal("items[0].name"))
        assertEquals("${'$'}items['sub-total']", signal("items['sub-total']"))
        assertEquals("${'$'}prefs['dark-mode'] = true", set("prefs['dark-mode']", true))
        assertEquals("${'$'}form.firstName['x-y']", signal("form.first-name['x-y']"))
        assertEquals("${'$'}counts[1]++", increment("counts[1]"))
        assertEquals(
            """<div data-class:hover:bg-red-500="${'$'}danger" data-attr:xlink:href="${'$'}href" data-style:--brand="'red'"></div>""",
            elements {
                div {
                    dataClass("hover:bg-red-500", signal("danger"))
                    dataAttr("xlink:href", signal("href"))
                    dataStyle("--brand", "'red'")
                }
            },
        )
    }

    /**
     * `data-bind`, `data-ref` and `data-indicator` take the signal name in the value, which keeps
     * its case, or in the key, which is the only place Datastar applies `__case`. The helpers
     * write the value unless a case is asked for, and read a kebab-case name as Datastar does.
     */
    @Test
    fun `bind, ref and indicator write the value unless a case is asked for`() {
        assertEquals(
            """<input data-bind="fooBar" data-ref="box" data-indicator="isBusy" data-bind__prop.checked__event.change="fooBar">""",
            elements {
                input {
                    dataBind("foo-bar")
                    dataRef("box")
                    dataIndicator("isBusy")
                    dataBind("fooBar") {
                        prop = "checked"
                        events = listOf("change")
                    }
                }
            },
        )
        assertEquals(
            """<input data-bind:foo-bar__case.kebab__event.input="" data-ref:my-ref__case.pascal="" data-indicator:busy__case.snake="">""",
            elements {
                input {
                    dataBind("fooBar") {
                        case = Case.KEBAB
                        events = listOf("input")
                    }
                    dataRef("MyRef", case = Case.PASCAL)
                    dataIndicator("busy", case = Case.SNAKE)
                }
            },
        )
        assertFailsWith<InvalidSignalNameException> { elements { input { dataBind("a b") } } }
    }

    @Test
    fun `regex filters carry their flags and keep escaped slashes`() {
        assertEquals("/^user/i", SignalFilter.regexLiteral(Regex("^user", RegexOption.IGNORE_CASE)))
        assertEquals(
            "/^a$/ims",
            SignalFilter.regexLiteral(Regex("^a$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))),
        )
        assertEquals("""/a\/b/""", SignalFilter.regexLiteral(Regex("a/b")))
        assertEquals("""/a\/b\\\/c/""", SignalFilter.regexLiteral(Regex("""a\/b\\/c""")))
        assertEquals("{include: /form/i}", SignalFilter(include = Regex("form", RegexOption.IGNORE_CASE)).toJs())
        assertFailsWith<IllegalArgumentException> { SignalFilter.regexLiteral(Regex("a b", RegexOption.COMMENTS)) }
    }

    /** Everything the helpers quote is single-quoted, so it survives a double-quoted attribute written by hand. */
    @Test
    fun `javascript literals use single quotes and bare keys`() {
        assertEquals("'it\\'s <\\/script>\\n'", JsLiteral.string("it's </script>\n"))
        assertEquals(
            "{id: 7, tags: ['a', 'b'], 'x-y': null, ok: true, n: 1.5}",
            JsLiteral.write(
                mapOf(
                    "id" to 7,
                    "tags" to listOf("a", "b"),
                    "x-y" to null,
                    "ok" to true,
                    "n" to 1.5,
                ),
            ),
        )
        assertEquals("['CAMEL', 'B']", JsLiteral.write(arrayOf(Case.CAMEL.name, "B")))
        assertEquals(
            """<button data-on:click="@post('/x', {headers: {'X-Csrf-Token': 't'}})">x</button>""",
            elements {
                button {
                    dataOnClick(
                        post("/x") {
                            headers =
                                mapOf("X-Csrf-Token" to "t")
                        },
                    )
                    ; +"x"
                }
            },
        )
        assertEquals(
            """<div data-on:click="${'$'}user.name = 'O\'Neil'"></div>""",
            elements { div { dataOnClick(set("user.name", "O'Neil")) } },
        )
    }
}
