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
import kotlinx.html.svg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
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
                    dataSignals("open", "false")
                    dataComputed("double", "${'$'}count * 2")
                    dataJsonSignals(SignalFilter.include("^user"), terse = true)
                }
            }
        assertEquals(
            """<div data-signals__ifmissing="{&quot;count&quot;:0,&quot;user&quot;:{&quot;name&quot;:&quot;Gorvek&quot;}}" """ +
                """data-signals:open="false" data-computed:double="${'$'}count * 2" """ +
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
            """<input data-bind__event.input.blur="search" data-ref="box" data-text="${'$'}search" """ +
                """data-show="!${'$'}hidden" data-class:active="${'$'}on" data-style:color="'red'" data-attr:disabled="${'$'}busy" """ +
                """data-ignore__self="" data-ignore-morph="" data-preserve-attr="open class" """ +
                """data-init__delay.500ms="${'$'}count = 1" data-effect="${'$'}count++">""",
            html,
        )
    }

    @Test
    fun `actions render options only when set and quote uris safely`() {
        assertEquals("""@get('/a')""", get("/a"))
        assertEquals("""@delete('/x?y=\x271\x27')""", delete("/x?y='1'"))
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

    // The browser lowercases an attribute name, so dataAttr("viewBox") could only set view-box.
    @Test
    fun `an attribute key with a capital is refused with the forms that work`() {
        val e = assertFailsWith<InvalidSignalNameException> { elements { div { dataAttr("viewBox", "'0 0 1 1'") } } }
        assertTrue("write view-box for an attribute that is kebab-case" in e.message!!, e.message)
        assertTrue("""the object form dataAttr("{viewBox: ...}")""" in e.message!!, e.message)
        assertFailsWith<InvalidSignalNameException> { elements { div { dataAttr("ariaLabel", "'x'") } } }
        assertEquals(
            """<svg xmlns="http://www.w3.org/2000/svg" data-attr="{viewBox: '0 0 1 1'}" data-attr:aria-label="'x'"></svg>""",
            elements {
                svg {
                    dataAttr("{viewBox: '0 0 1 1'}")
                    dataAttr("aria-label", "'x'")
                }
            },
        )
    }

    // kotlinx.html writes a backslash before & as a bare &; conformance/dsl holds what the client makes of each.
    @Test
    fun `a backslash before an ampersand reaches the browser as written`() {
        assertEquals("a", DatastarAttributes.value("a"))
        assertEquals("'\\&#92;\\&amp;amp;'", DatastarAttributes.value("'\\&amp;'"))
        assertEquals(
            // A filter's regex needs none of it: & is written as \x26 there.
            """<div data-text="'&#92;&amp;amp;'" data-json-signals="{include: /^a\x26b${'$'}/}"></div>""",
            elements {
                div {
                    dataText("'\\&amp;'")
                    dataJsonSignals(SignalFilter(include = Regex("^a\\&b${'$'}")))
                }
            },
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
        assertEquals("""'a\x27b'""", js("a'b"))
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
                    dataAttr("aria-label", "'x'")
                }
            }
        assertEquals(
            """<div data-signals:foo-bar="1" data-signals:form.first-name__ifmissing="''" """ +
                """data-computed:full-name="${'$'}first + ${'$'}last" """ +
                """data-on:widget-loaded__once__case.camel="x()" data-class:is-open__case.camel="${'$'}open" """ +
                """data-style:background-color="'red'" data-attr:aria-label="'x'"></div>""",
            html,
        )
        // Snake or lower-case: written as given. A hyphen is refused, since it would not come back as written.
        assertEquals(
            """<div data-signals:foo_bar="2" data-on:my-event="x()"></div>""",
            elements {
                div {
                    dataSignals("foo_bar", "2")
                    dataOn("my-event", "x()")
                }
            },
        )
        val hyphen = assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("foo-bar", "1") } } }
        assertTrue("write fooBar" in hyphen.message!!, hyphen.message)
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
        // A helper writes the name as it stands, and refuses a hyphen the client reads in a name or as a minus.
        assertEquals("${'$'}fooBar", signal("fooBar"))
        assertEquals("${'$'}form.firstName++", increment("form.firstName"))
        val kebab = assertFailsWith<InvalidSignalNameException> { signal("form.first-name") }
        assertTrue("write form.firstName, or form['first-name']" in kebab.message!!, kebab.message)
        val bracketed = assertFailsWith<InvalidSignalNameException> { signal("form.first-name['x-y']") }
        assertTrue(bracketed.message!!.endsWith("write form.firstName['x-y']."), bracketed.message)
        assertEquals("${'$'}a[i-1]", signal("a[i-1]"))
        // The name is read as the client reads it: a quoted key is text, even with a bracket in it,
        // while a reference in brackets is a signal of its own and a bare hyphen after a key a minus.
        assertEquals("${'$'}a['x]y-z']", signal("a['x]y-z']"))
        val inner = assertFailsWith<InvalidSignalNameException> { signal("items[${'$'}i-1]") }
        assertTrue(inner.message!!.endsWith("write it in the expression: ${'$'}items[${'$'}i - 1]."), inner.message)
        val index = assertFailsWith<InvalidSignalNameException> { signal("items[${'$'}i-x]") }
        assertTrue(index.message!!.endsWith("write items[${'$'}iX]."), index.message)
        // The text of a template literal is text, though the client reads one with `${'$'}{` as code.
        assertEquals("${'$'}a[`x]-${'$'}{${'$'}b}`]", signal("a[`x]-${'$'}{${'$'}b}`]"))
        val minus = assertFailsWith<InvalidSignalNameException> { signal("a['['].b-c") }
        assertTrue(minus.message!!.endsWith("write a['['].bC."), minus.message)
        assertFailsWith<InvalidSignalNameException> { signal("") }
        assertFailsWith<InvalidSignalNameException> { signal("my signal") }
        assertFailsWith<InvalidSignalNameException> { elements { div { dataSignals("a b", "1") } } }
        // Index and bracket references, Tailwind variants and namespaced attributes are keys and names too.
        assertEquals("${'$'}items[0].name", signal("items[0].name"))
        assertEquals("${'$'}items['sub-total']", signal("items['sub-total']"))
        assertEquals("${'$'}prefs['dark-mode'] = true", set("prefs['dark-mode']", true))
        assertEquals("${'$'}form.firstName['x-y']", signal("form.firstName['x-y']"))
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

    /** `data-bind`, `data-ref` and `data-indicator` take the signal name in the value, which keeps its case. */
    @Test
    fun `bind, ref and indicator write the name in the value`() {
        assertEquals(
            """<input data-bind="fooBar" data-ref="MyRef" data-indicator="isBusy" data-bind__prop.checked__event.change="fooBar">""",
            elements {
                input {
                    dataBind("fooBar")
                    dataRef("MyRef")
                    dataIndicator("isBusy")
                    dataBind("fooBar") {
                        prop = "checked"
                        events = listOf("change")
                    }
                }
            },
        )
        assertFailsWith<InvalidSignalNameException> { elements { input { dataBind("a b") } } }
        assertFailsWith<InvalidSignalNameException> { elements { input { dataBind("foo-bar") } } }
        assertFailsWith<InvalidSignalNameException> { elements { input { dataRef("my-ref") } } }
        assertFailsWith<InvalidSignalNameException> { elements { input { dataIndicator("is-busy") } } }
    }

    @Test
    fun `regex filters carry their flags and keep escaped slashes`() {
        assertEquals("/^user/i", SignalFilter.regexLiteral(Regex("^user", RegexOption.IGNORE_CASE)))
        assertEquals(
            "/^a$/ims",
            SignalFilter.regexLiteral(Regex("^a$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))),
        )
        assertEquals("""/a\/b/""", SignalFilter.regexLiteral(Regex("a/b")))
        assertEquals("""/a\/b\x5c\/c/""", SignalFilter.regexLiteral(Regex("""a\/b\\/c""")))
        assertEquals("{include: /form/i}", SignalFilter(include = Regex("form", RegexOption.IGNORE_CASE)).toJs())
        assertFailsWith<IllegalArgumentException> { SignalFilter.regexLiteral(Regex("a b", RegexOption.COMMENTS)) }
    }

    /** Everything the helpers quote is single-quoted, so it survives a double-quoted attribute written by hand. */
    @Test
    fun `javascript literals use single quotes and bare keys`() {
        assertEquals("'it\\x27s <\\/script>\\n'", JsLiteral.string("it's </script>\n"))
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
            """<div data-on:click="${'$'}user.name = 'O\x27Neil'"></div>""",
            elements { div { dataOnClick(set("user.name", "O'Neil")) } },
        )
    }

    // Datastar raises NonceRequired for an empty data-nonce and does not start.
    @Test
    fun `an empty nonce is refused before it reaches the markup`() {
        assertFailsWith<IllegalArgumentException> {
            kotlinx.html.stream
                .createHTML()
                .html { dataNonce("") }
        }
    }

    // The browser lowercases the attribute name; the client camel-cases the modifier back.
    @Test
    fun `a bind property is written in kebab case`() {
        val html =
            kotlinx.html.stream
                .createHTML()
                .input { dataBind("n") { prop = "valueAsNumber" } }

        assertEquals("""<input data-bind__prop.value-as-number="n">""", html)
    }

    @Test
    fun `a name holding two underscores is refused, because Datastar reads modifiers from there`() {
        assertFailsWith<InvalidSignalNameException> {
            kotlinx.html.stream
                .createHTML()
                .div { dataOn("my__event", "x()") }
        }
    }

    @Test
    fun `a duration shorter than a millisecond is refused rather than written as zero`() {
        assertFailsWith<IllegalArgumentException> {
            kotlinx.html.stream
                .createHTML()
                .button { dataOnClick("x()") { debounce = 500.microseconds } }
        }
        assertFailsWith<IllegalArgumentException> { get("/x") { retryInterval = 500.microseconds } }
        assertFailsWith<IllegalArgumentException> { get("/x") { retryScaler = Double.NaN } }
    }

    @Test
    fun `a signal filter is always a regex literal JavaScript can read`() {
        assertEquals("/(?:)/", SignalFilter.regexLiteral(Regex("")))
        assertEquals("/a\\nb/", SignalFilter.regexLiteral(Regex("a\nb")))
        assertFailsWith<IllegalArgumentException> { SignalFilter.regexLiteral(Regex("(?i)a")) }
    }
}
