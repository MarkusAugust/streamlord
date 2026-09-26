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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HtmlDslTest {

    @Test
    fun `elements renders compact html`() {
        assertEquals("""<div id="a"><span>x</span></div>""", elements { div { id = "a"; span { +"x" } } })
    }

    @Test
    fun `patchElements from the dsl goes straight to the stream`() = runTest {
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
        val html = elements {
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
        val html = elements {
            button {
                dataOnClick(post("/add")) { debounce = 300.milliseconds; debounceLeading = true; prevent = true; viewTransition = true }
                dataOn("keydown", "evt.key === 'Escape' && (${set("open", false)})") { window = true; once = true }
                dataOnInterval(get("/tick")) { duration = 2.seconds; leading = true }
                dataOnIntersect(get("/more")) { once = true; threshold = 50 }
                dataOnSignalPatch("console.log(patch)", SignalFilter.exclude("^_")) { throttle = 1.seconds }
                dataIndicator("busy")
            }
        }
        assertEquals(
            """<button data-on:click__debounce.300ms.leading__viewtransition__prevent="@post(&quot;/add&quot;)" """ +
                """data-on:keydown__once__window="evt.key === 'Escape' &amp;&amp; (${'$'}open = false)" """ +
                """data-on-interval__duration.2000ms.leading="@get(&quot;/tick&quot;)" """ +
                """data-on-intersect__once__threshold.50="@get(&quot;/more&quot;)" """ +
                """data-on-signal-patch__throttle.1000ms="console.log(patch)" data-on-signal-patch-filter="{exclude: /^_/}" """ +
                """data-indicator="busy"></button>""",
            html,
        )
    }

    @Test
    fun `binding display and morph attributes`() {
        val html = elements {
            input {
                dataBind("search") { case = Case.CAMEL; events = listOf("input", "blur") }
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
            """<input data-bind__case.camel__event.input.blur="search" data-ref="box" data-text="${'$'}search" """ +
                """data-show="!${'$'}hidden" data-class:active="${'$'}on" data-style:color="'red'" data-attr:disabled="${'$'}busy" """ +
                """data-ignore__self="" data-ignore-morph="" data-preserve-attr="open class" """ +
                """data-init__delay.500ms="${'$'}count = 1" data-effect="${'$'}count++">""",
            html,
        )
    }

    @Test
    fun `actions render options only when set and quote uris safely`() {
        assertEquals("""@get("/a")""", get("/a"))
        assertEquals("""@delete("/x?y='1'")""", delete("/x?y='1'"))
        assertEquals("""@query("/search")""", query("/search"))
        assertEquals(
            """@post("/form", {contentType: "form", filterSignals: {include: /^form\//}, selector: "#f", headers: {"X-Csrf-Token":"t"}, openWhenHidden: true, retry: "never", retryInterval: 500, requestCancellation: "cleanup"})""",
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
        assertEquals("@toggleAll()", toggleAll())
        assertEquals("@peek(() => ${'$'}count)", peek(signal("count")))
    }

    @Test
    fun `payload and response overrides`() {
        assertEquals(
            """@post("/x", {payload: {"id":7}, responseOverrides: {selector: "#out", mode: "inner", useViewTransition: true}})""",
            post("/x") {
                payload = mapOf("id" to 7)
                responseOverrides { selector = "#out"; mode = ElementPatchMode.INNER; useViewTransition = true }
            },
        )
        assertEquals("""@get("/x", {payload: {id: ${'$'}selected}, responseOverrides: {onlyIfMissing: true}})""", get("/x") { payloadExpr = "{id: ${'$'}selected}"; responseOverrides { onlyIfMissing = true } })
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
                elements { div { dataSignals("n" to 1); dataOnClick("x()") { once = true }; dataIgnore(self = true); dataJsonSignals() } },
            )
        } finally {
            DatastarAttributes.prefix = "data-"
        }
    }

    @Test
    fun `remaining event sugar, nonce, verbs and responses`() {
        val html = elements {
            html {
                dataNonce("n0nce")
                form {
                    dataOnSubmit(post("/save"))
                    input { dataOnInput(put("/draft")); dataOnChange(patch("/field")); dataOnKeydown("evt.key") }
                }
            }
        }
        assertEquals(
            """<html data-nonce="n0nce"><form data-on:submit="@post(&quot;/save&quot;)">""" +
                """<input data-on:input="@put(&quot;/draft&quot;)" data-on:change="@patch(&quot;/field&quot;)" data-on:keydown="evt.key"></form></html>""",
            html,
        )
        assertEquals("\"a'b\"", js("a'b"))
        val response = elementsResponse(selector = "#out", mode = ElementPatchMode.INNER) { span { +"x" } }
        assertEquals("<span>x</span>", response.body)
        assertEquals(mapOf("datastar-selector" to "#out", "datastar-mode" to "inner"), response.headers)
    }

    @Test
    fun `expression helpers`() {
        assertEquals("${'$'}user.name = \"Gorvek\"; ${'$'}open = !${'$'}open; ${'$'}n--", statements(set("user.name", "Gorvek"), toggle("open"), decrement("n")))
        assertEquals("${'$'}a = ${'$'}b + 1", setExpr("a", "${'$'}b + 1"))
    }
}
