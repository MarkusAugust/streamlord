package io.github.markusaugust.streamlord.html.pro

import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.html.Case
import io.github.markusaugust.streamlord.html.FetchOptions
import io.github.markusaugust.streamlord.html.SignalFilter
import io.github.markusaugust.streamlord.html.dataAttr
import io.github.markusaugust.streamlord.html.dataBind
import io.github.markusaugust.streamlord.html.dataClass
import io.github.markusaugust.streamlord.html.dataComputed
import io.github.markusaugust.streamlord.html.dataEffect
import io.github.markusaugust.streamlord.html.dataIgnore
import io.github.markusaugust.streamlord.html.dataIgnoreMorph
import io.github.markusaugust.streamlord.html.dataIndicator
import io.github.markusaugust.streamlord.html.dataInit
import io.github.markusaugust.streamlord.html.dataJsonSignals
import io.github.markusaugust.streamlord.html.dataNonce
import io.github.markusaugust.streamlord.html.dataOn
import io.github.markusaugust.streamlord.html.dataOnChange
import io.github.markusaugust.streamlord.html.dataOnClick
import io.github.markusaugust.streamlord.html.dataOnFetch
import io.github.markusaugust.streamlord.html.dataOnInput
import io.github.markusaugust.streamlord.html.dataOnIntersect
import io.github.markusaugust.streamlord.html.dataOnInterval
import io.github.markusaugust.streamlord.html.dataOnKeydown
import io.github.markusaugust.streamlord.html.dataOnSignalPatch
import io.github.markusaugust.streamlord.html.dataOnSubmit
import io.github.markusaugust.streamlord.html.dataPreserveAttr
import io.github.markusaugust.streamlord.html.dataRef
import io.github.markusaugust.streamlord.html.dataShow
import io.github.markusaugust.streamlord.html.dataSignals
import io.github.markusaugust.streamlord.html.dataStyle
import io.github.markusaugust.streamlord.html.dataText
import io.github.markusaugust.streamlord.html.decrement
import io.github.markusaugust.streamlord.html.delete
import io.github.markusaugust.streamlord.html.get
import io.github.markusaugust.streamlord.html.increment
import io.github.markusaugust.streamlord.html.js
import io.github.markusaugust.streamlord.html.not
import io.github.markusaugust.streamlord.html.patch
import io.github.markusaugust.streamlord.html.peek
import io.github.markusaugust.streamlord.html.post
import io.github.markusaugust.streamlord.html.put
import io.github.markusaugust.streamlord.html.query
import io.github.markusaugust.streamlord.html.set
import io.github.markusaugust.streamlord.html.setAll
import io.github.markusaugust.streamlord.html.setExpr
import io.github.markusaugust.streamlord.html.signal
import io.github.markusaugust.streamlord.html.statements
import io.github.markusaugust.streamlord.html.toggle
import io.github.markusaugust.streamlord.html.toggleAll
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.option
import kotlinx.html.pre
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.svg
import kotlinx.html.textArea
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the free Datastar client does with the HTML the DSL writes, held to what each call means:
 * `conformance/dsl/free.json`, filled in by `conformance/generate/dsl.mjs` running the client's
 * own bundle. Each case says, as literals, what the user asked for: the event the client should
 * listen to, the signal it should store, the class it should set. An expression calls `probe()`
 * where the case counts its runs.
 */
class DslAsTheClientReadsItTest {
    private val fixture =
        ClientReadings(
            "free.json",
            "What the free Datastar client did with the HTML the DSL writes: each case's name, call, prefix, probe and html " +
                "come from DslAsTheClientReadsItTest under ./gradlew :streamlord-html-pro:updateClientReadings, its read from " +
                "conformance/generate/dsl.mjs, which runs the client's bundle in happy-dom.",
        )

    private val burst = mapOf("burst" to 2, "rounds" to 1)

    private val events =
        listOf(
            dslCase("on click", "dataOnClick", expect = {
                listens("button", "click")
                heard("button", "click", 1, 1)
            }) { button { dataOnClick("probe(evt)") } },
            dslCase("on submit", "dataOnSubmit", expect = {
                listens("form", "submit")
                // The client stops a form's own submission of itself.
                heard("form", "submit", 1, 1, prevented = true)
            }) { form { dataOnSubmit("probe(evt)") } },
            dslCase("on change", "dataOnChange", expect = {
                listens("input", "change")
                heard("input", "change", 1, 1)
            }) { input { dataOnChange("probe(evt)") } },
            dslCase("on input", "dataOnInput", expect = {
                listens("input", "input")
                heard("input", "input", 1, 1)
            }) { input { dataOnInput("probe(evt)") } },
            dslCase("on keydown", "dataOnKeydown", expect = {
                listens("input", "keydown")
                heard("input", "keydown", 1, 1)
            }) { input { dataOnKeydown("probe(evt)") } },
            dslCase("on fetch", "dataOnFetch", expect = {
                listens("document", "datastar-fetch")
            }) { div { dataOnFetch("probe(evt)") } },
            dslCase("on a kebab-case event", "dataOn", key = "my-event", expect = {
                listens("button", "my-event")
                heard("button", "my-event", 1, 1)
            }) { button { dataOn("my-event", "probe(evt)") } },
            dslCase("on a camelCase event once", "dataOn", key = "widgetLoaded", expect = {
                listens("button", "widgetLoaded", once = true)
                heard("button", "widgetLoaded", 1, 0)
            }) { button { dataOn("widgetLoaded", "probe(evt)") { once = true } } },
            dslCase("on a PascalCase event", "dataOn", key = "WidgetLoaded", expect = {
                listens("button", "WidgetLoaded")
                heard("button", "WidgetLoaded", 1, 1)
            }) { button { dataOn("WidgetLoaded", "probe(evt)") } },
            dslCase("on an acronym event", "dataOn", key = "DOMContentLoaded", expect = {
                listens("button", "DOMContentLoaded")
                heard("button", "DOMContentLoaded", 1, 1)
            }) { button { dataOn("DOMContentLoaded", "probe(evt)") } },
            dslCase("on an event with a digit", "dataOn", key = "item-2", expect = {
                listens("button", "item-2")
                heard("button", "item-2", 1, 1)
            }) { button { dataOn("item-2", "probe(evt)") } },
            dslCase("on a snake_case event", "dataOn", key = "my_event", expect = {
                listens("button", "my_event")
                heard("button", "my_event", 1, 1)
            }) { button { dataOn("my_event", "probe(evt)") } },
            dslCase("on with case camel", "dataOn", key = "my-event", case = "camel", expect = {
                // modifyCasing("my-event", case.camel) in conformance/client/casing.json.
                listens("button", "myEvent")
                heard("button", "myEvent", 1, 1)
            }) { button { dataOn("my-event", "probe(evt)") { case = Case.CAMEL } } },
            dslCase("on with case snake", "dataOn", key = "my-event", case = "snake", expect = {
                listens("button", "my_event")
                heard("button", "my_event", 1, 1)
            }) { button { dataOn("my-event", "probe(evt)") { case = Case.SNAKE } } },
            dslCase("on with case pascal", "dataOn", key = "my-event", case = "pascal", expect = {
                listens("button", "MyEvent")
                heard("button", "MyEvent", 1, 1)
            }) { button { dataOn("my-event", "probe(evt)") { case = Case.PASCAL } } },
            dslCase("on with case kebab", "dataOn", key = "myEvent", case = "kebab", expect = {
                listens("button", "my-event")
                heard("button", "my-event", 1, 1)
            }) { button { dataOn("myEvent", "probe(evt)") { case = Case.KEBAB } } },
            dslCase("on passive", "dataOnClick", expect = {
                listens("button", "click", passive = true)
                heard("button", "click", 1, 1)
            }) { button { dataOnClick("probe(evt)") { passive = true } } },
            dslCase("on capture", "dataOnClick", expect = {
                listens("button", "click", capture = true)
                heard("button", "click", 1, 1)
            }) { button { dataOnClick("probe(evt)") { capture = true } } },
            dslCase("on prevent", "dataOnClick", expect = {
                listens("button", "click")
                heard("button", "click", 1, 1, prevented = true)
            }) { button { dataOnClick("probe(evt)") { prevent = true } } },
            dslCase("on stop", "dataOnClick", expect = {
                listens("button", "click")
                heard("button", "click", 1, 1, bubbled = false)
            }) { button { dataOnClick("probe(evt)") { stop = true } } },
            dslCase("on window", "dataOnKeydown", expect = {
                listens("window", "keydown")
                heard("window", "keydown", 1, 1)
            }) { div { dataOnKeydown("probe(evt)") { window = true } } },
            dslCase("on document", "dataOnKeydown", expect = {
                listens("document", "keydown")
                heard("document", "keydown", 1, 1)
            }) { div { dataOnKeydown("probe(evt)") { document = true } } },
            dslCase(
                "on outside",
                "dataOnClick",
                probe =
                    mapOf(
                        "dispatch" to listOf(listOf("button", "click"), listOf("document", "click")),
                        "rounds" to 1,
                    ),
                expect = {
                    listens("document", "click")
                    // A click on the element itself is not outside it.
                    heard("button", "click", 0)
                    heard("document", "click", 1)
                },
            ) { button { dataOnClick("probe(evt)") { outside = true } } },
            dslCase("on view transition", "dataOnClick", expect = {
                listens("button", "click")
                heard("button", "click", 1, 1)
                viewTransitions = 2
            }) { button { dataOnClick("probe(evt)") { viewTransition = true } } },
            dslCase("on passive and prevent", "dataOnClick", expect = {
                listens("button", "click", passive = true)
                // A passive listener cannot cancel the event.
                heard("button", "click", 1, 1)
            }) {
                button {
                    dataOnClick("probe(evt)") {
                        passive = true
                        prevent = true
                    }
                }
            },
            dslCase("on every flag", "dataOn", key = "widgetLoaded", expect = {
                listens("window", "widgetLoaded", capture = true, passive = true, once = true)
                heard("window", "widgetLoaded", 1, 0)
                viewTransitions = 1
            }) {
                div {
                    dataOn("widgetLoaded", "probe(evt)") {
                        once = true
                        passive = true
                        capture = true
                        window = true
                        prevent = true
                        stop = true
                        viewTransition = true
                    }
                }
            },
        )

    private val timing =
        listOf(
            dslCase("on delay", "dataOnClick", expect = {
                listens("button", "click")
                timer("setTimeout", 500)
                timer("setTimeout", 500)
                heard("button", "click", 1, 1, sync = listOf(0, 0))
            }) { button { dataOnClick("probe(evt)") { delay = 500.milliseconds } } },
            // Each of these sends two clicks before any timer runs, as a quick double click does,
            // so the leading and trailing runs of timing.ts's throttle can be told apart.
            dslCase("on debounce", "dataOnClick", probe = burst, expect = {
                listens("button", "click")
                timer("setTimeout", 1000)
                timer("setTimeout", 1000)
                heard("button", "click", 1, sync = listOf(0))
            }) { button { dataOnClick("probe(evt)") { debounce = 1.seconds } } },
            dslCase("on debounce leading", "dataOnClick", probe = burst, expect = {
                listens("button", "click")
                timer("setTimeout", 500)
                timer("setTimeout", 500)
                heard("button", "click", 2, sync = listOf(1))
            }) {
                button {
                    dataOnClick("probe(evt)") {
                        debounce = 500.milliseconds
                        debounceLeading = true
                    }
                }
            },
            dslCase("on debounce leading without trailing", "dataOnClick", probe = burst, expect = {
                listens("button", "click")
                timer("setTimeout", 500)
                timer("setTimeout", 500)
                heard("button", "click", 1, sync = listOf(1))
            }) {
                button {
                    dataOnClick("probe(evt)") {
                        debounce = 500.milliseconds
                        debounceLeading = true
                        debounceNoTrailing = true
                    }
                }
            },
            dslCase("on throttle", "dataOnClick", probe = burst, expect = {
                listens("button", "click")
                timer("setTimeout", 1000)
                heard("button", "click", 1, sync = listOf(1))
            }) { button { dataOnClick("probe(evt)") { throttle = 1.seconds } } },
            dslCase("on throttle without leading", "dataOnClick", probe = burst, expect = {
                listens("button", "click")
                timer("setTimeout", 1000)
                // Without trailing either, nothing is left to run.
                heard("button", "click", 0)
            }) {
                button {
                    dataOnClick("probe(evt)") {
                        throttle = 1.seconds
                        throttleNoLeading = true
                    }
                }
            },
            dslCase("on throttle trailing", "dataOnClick", probe = burst, expect = {
                listens("button", "click")
                timer("setTimeout", 1000)
                heard("button", "click", 2, sync = listOf(1))
            }) {
                button {
                    dataOnClick("probe(evt)") {
                        throttle = 1.seconds
                        throttleTrailing = true
                    }
                }
            },
            dslCase("on delay, debounce and throttle together", "dataOnClick", probe = burst, expect = {
                listens("button", "click")
                // modifyTiming wraps the delay first, then the debounce, then the throttle: the first
                // click passes the throttle into the debounce, the second is held by the throttle,
                // and the debounce's run then waits out the delay.
                timer("setTimeout", 20)
                timer("setTimeout", 50)
                timer("setTimeout", 10)
                heard("button", "click", 1, sync = listOf(0))
            }) {
                button {
                    dataOnClick("probe(evt)") {
                        delay = 10.milliseconds
                        debounce = 20.milliseconds
                        throttle = 50.milliseconds
                    }
                }
            },
            dslCase("on a zero delay", "dataOnClick", expect = {
                listens("button", "click")
                timer("setTimeout", 0)
                timer("setTimeout", 0)
                heard("button", "click", 1, 1, sync = listOf(0, 0))
            }) { button { dataOnClick("probe(evt)") { delay = Duration.ZERO } } },
            dslCase("on the longest delay", "dataOnClick", expect = {
                listens("button", "click")
                timer("setTimeout", Int.MAX_VALUE)
                timer("setTimeout", Int.MAX_VALUE)
                heard("button", "click", 1, 1, sync = listOf(0, 0))
            }) { button { dataOnClick("probe(evt)") { delay = Int.MAX_VALUE.milliseconds } } },
            dslCase("on a fractional delay", "dataOnClick", expect = {
                listens("button", "click")
                // Written in whole milliseconds, a fraction rounded down.
                timer("setTimeout", 1)
                timer("setTimeout", 1)
                heard("button", "click", 1, 1, sync = listOf(0, 0))
            }) { button { dataOnClick("probe(evt)") { delay = 1.5.milliseconds } } },
        )

    private val observers =
        listOf(
            dslCase("on intersect", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 0), "div")
                intersects(1, 0)
            }) { div { dataOnIntersect("probe(1)") } },
            dslCase("on intersect half", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 0.5), "div")
                intersects(1, 0)
            }) { div { dataOnIntersect("probe(1)") { half = true } } },
            dslCase("on intersect full", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 1), "div")
                intersects(1, 0)
            }) { div { dataOnIntersect("probe(1)") { full = true } } },
            dslCase("on intersect threshold", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 0.25), "div")
                intersects(1, 0)
            }) { div { dataOnIntersect("probe(1)") { threshold = 25 } } },
            dslCase("on intersect threshold 0 and 100", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 0), "div")
                observes(mapOf("threshold" to 1), "span")
                intersects(1, 0)
                intersects(1, 0)
            }) {
                div { dataOnIntersect("probe(1)") { threshold = 0 } }
                span { dataOnIntersect("probe(2)") { threshold = 100 } }
            },
            dslCase("on intersect exit", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 0), "div")
                intersects(0, 1)
            }) { div { dataOnIntersect("probe(1)") { exit = true } } },
            dslCase("on intersect once", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 0), "div")
                intersects(1, 0)
            }) { div { dataOnIntersect("probe(1)") { once = true } } },
            dslCase("on intersect debounce", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 0), "div")
                timer("setTimeout", 200)
                intersects(1, 0)
            }) { div { dataOnIntersect("probe(1)") { debounce = 200.milliseconds } } },
            dslCase("on intersect with every timing modifier", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 0), "div")
                // The throttle holds the entry for its trailing run, which passes the debounce at
                // once as its leading run and then waits out the delay.
                timer("setTimeout", 30)
                timer("setTimeout", 10)
                timer("setTimeout", 20)
                intersects(1, 0)
            }) {
                div {
                    dataOnIntersect("probe(1)") {
                        delay = 10.milliseconds
                        debounce = 20.milliseconds
                        debounceLeading = true
                        debounceNoTrailing = true
                        throttle = 30.milliseconds
                        throttleNoLeading = true
                        throttleTrailing = true
                    }
                }
            },
            dslCase("on intersect view transition", "dataOnIntersect", expect = {
                observes(mapOf("threshold" to 0), "div")
                intersects(1, 0)
                viewTransitions = 1
            }) { div { dataOnIntersect("probe(1)") { viewTransition = true } } },
            dslCase("on interval", "dataOnInterval", expect = {
                timer("setInterval", 1000)
                probes = listOf(listOf(1))
            }) { div { dataOnInterval("probe(1)") } },
            dslCase("on interval duration leading", "dataOnInterval", expect = {
                timer("setInterval", 250)
                probes = listOf(listOf(1), listOf(1))
            }) {
                div {
                    dataOnInterval("probe(1)") {
                        duration = 250.milliseconds
                        leading = true
                    }
                }
            },
            dslCase("on interval view transition", "dataOnInterval", expect = {
                timer("setInterval", 1000)
                probes = listOf(listOf(1))
                viewTransitions = 1
            }) { div { dataOnInterval("probe(1)") { viewTransition = true } } },
            dslCase("init", "dataInit", expect = { probes = listOf(listOf("i")) }) { div { dataInit("probe('i')") } },
            dslCase("init delay", "dataInit", expect = {
                timer("setTimeout", 300)
                probes = listOf(listOf("i"))
            }) { div { dataInit("probe('i')") { delay = 300.milliseconds } } },
            dslCase("init view transition", "dataInit", expect = {
                probes = listOf(listOf("i"))
                viewTransitions = 1
            }) { div { dataInit("probe('i')") { viewTransition = true } } },
            dslCase("effect", "dataEffect", expect = { probes = listOf(listOf("e")) }) { div { dataEffect("probe('e')") } },
            dslCase("on signal patch", "dataOnSignalPatch", probe = mapOf("eval" to "Datastar.mergePatch({a: 2})"), expect = {
                listensPlain("document", "datastar-signal-patch")
                store = """{"a":1}"""
                storeAfter = """{"a":2}"""
                probes = listOf(listOf(mapOf("a" to 2)))
            }) {
                div {
                    dataSignals("a", "1")
                    dataOnSignalPatch("probe(patch)")
                }
            },
            dslCase(
                "on signal patch filtered",
                "dataOnSignalPatch",
                probe =
                    mapOf("eval" to "Datastar.mergePatch({a: 2}); Datastar.mergePatch({b: 3})"),
                expect = {
                    listensPlain("document", "datastar-signal-patch")
                    store = """{"a":1,"b":1}"""
                    storeAfter = """{"a":2,"b":3}"""
                    probes = listOf(listOf(mapOf("b" to 3)))
                },
            ) {
                div {
                    dataSignals("a" to 1, "b" to 1)
                    dataOnSignalPatch("probe(patch)", SignalFilter(include = Regex("^b$")))
                }
            },
            dslCase(
                "on signal patch with every timing modifier",
                "dataOnSignalPatch",
                probe =
                    mapOf(
                        "eval" to "Datastar.mergePatch({a: 2})",
                    ),
                expect = {
                    listensPlain("document", "datastar-signal-patch")
                    timer("setTimeout", 30)
                    timer("setTimeout", 10)
                    timer("setTimeout", 20)
                    store = """{"a":1}"""
                    storeAfter = """{"a":2}"""
                    probes = listOf(listOf(mapOf("a" to 2)))
                },
            ) {
                div {
                    dataSignals("a", "1")
                    dataOnSignalPatch("probe(patch)") {
                        delay = 10.milliseconds
                        debounce = 20.milliseconds
                        debounceLeading = true
                        debounceNoTrailing = true
                        throttle = 30.milliseconds
                        throttleNoLeading = true
                        throttleTrailing = true
                    }
                }
            },
            dslCase("on signal patch debounce", "dataOnSignalPatch", probe = mapOf("eval" to "Datastar.mergePatch({a: 2})"), expect = {
                listensPlain("document", "datastar-signal-patch")
                timer("setTimeout", 100)
                store = """{"a":1}"""
                storeAfter = """{"a":2}"""
                probes = listOf(listOf(mapOf("a" to 2)))
            }) {
                div {
                    dataSignals("a", "1")
                    dataOnSignalPatch("probe(patch)") { debounce = 100.milliseconds }
                }
            },
        )

    private val signals =
        listOf(
            dslCase("signals keyed camelCase", "dataSignals", key = "fooBar", expect = {
                store = """{"fooBar":1}"""
            }) { div { dataSignals("fooBar", "1") } },
            dslCase("signals keyed dotted", "dataSignals", key = "user.firstName", expect = {
                store = """{"user":{"firstName":"x"}}"""
            }) { div { dataSignals("user.firstName", "'x'") } },
            dslCase("signals keyed names", "dataSignals", expect = {
                store = """{"URLBase":1,"x2y":2,"v2Api":3,"_private":4,"b_":5,"a":7}"""
            }) {
                div {
                    dataSignals("URLBase", "1")
                    dataSignals("x2y", "2")
                    dataSignals("v2Api", "3")
                    dataSignals("_private", "4")
                    dataSignals("b_", "5")
                    dataSignals("a", "7")
                }
            },
            dslCase("signals keyed if missing", "dataSignals", key = "a", expect = {
                store = """{"a":1}"""
            }) {
                div {
                    dataSignals("a", "1")
                    div { dataSignals("a", "2", ifMissing = true) }
                }
            },
            dslCase("signals object form", "dataSignals", expect = {
                store = """{"a":1,"b":{"c":"x"}}"""
            }) { div { dataSignals("{a: 1, b: {c: 'x'}}") } },
            dslCase("signals object form if missing", "dataSignals", expect = {
                store = """{"a":1,"b":2}"""
            }) {
                div {
                    dataSignals("a", "1")
                    div { dataSignals("{a: 2, b: 2}", ifMissing = true) }
                }
            },
            dslCase("signals pairs", "dataSignals", expect = {
                // The client holds no null: a patch's null removes the key, so "none" is not
                // declared, and reading $none would give "" (signals.md).
                store = """{"count":0,"user":{"firstName":"","tags":["a","b"]},"ratio":0.5,"on":true}"""
            }) {
                div {
                    dataSignals(
                        "count" to 0,
                        "user.firstName" to "",
                        "user.tags" to listOf("a", "b"),
                        "ratio" to 0.5,
                        "on" to true,
                        "none" to null,
                    )
                }
            },
            dslCase("signals pairs with a nested key as written", "dataSignals", expect = {
                store = """{"user":{"first-name":""}}"""
            }) { div { dataSignals("user" to mapOf("first-name" to "")) } },
            dslCase("signals pairs if missing", "dataSignals", expect = {
                store = """{"a":1,"b":2}"""
            }) {
                div {
                    dataSignals("a" to 1)
                    div { dataSignals("a" to 2, "b" to 2, ifMissing = true) }
                }
            },
            dslCase("signals from a JSON value", "dataSignals", expect = {
                // A null inside an array is read back as "", as every null the client reads is.
                // The big number is a JavaScript number in the client, as JSON.parse makes it.
                store = """{"a":1,"b":{"c":[true,""]},"big":12345678901234567890}"""
            }) { div { dataSignals(JsonParser.parse("""{"a":1,"b":{"c":[true,null]},"big":12345678901234567890}""")) } },
            dslCase("signals with strings the HTML and JS must carry", "dataSignals", expect = {
                store = """{"s":"a\"b'c&amp;<\/script>\n\\  ;"}"""
            }) { div { dataSignals("s" to "a\"b'c&amp;</script>\n\\  ;") } },
            dslCase("computed keyed", "dataComputed", key = "total", expect = {
                store = """{"a":1,"total":2}"""
            }) {
                div {
                    dataSignals("a", "1")
                    dataComputed("total", "${'$'}a + 1")
                }
            },
            dslCase("computed keyed PascalCase", "dataComputed", key = "Total", expect = {
                store = """{"a":1,"Total":2}"""
            }) {
                div {
                    dataSignals("a", "1")
                    dataComputed("Total", "${'$'}a + 1")
                }
            },
            dslCase("computed object form", "dataComputed", expect = {
                store = """{"a":1,"double":2}"""
            }) {
                div {
                    dataSignals("a", "1")
                    dataComputed("{double: () => ${'$'}a * 2}")
                }
            },
            dslCase("ref", "dataRef", key = "myEl", expect = { store = """{"myEl":"<div>"}""" }) { div { dataRef("myEl") } },
            dslCase("indicator", "dataIndicator", key = "loading", probe = mapOf("rounds" to 1), expect = {
                listensPlain("document", "datastar-fetch")
                listens("button", "click")
                listensPlain("document", "visibilitychange")
                store = """{"loading":false}"""
                // The indicator is set before the signals are read for the request.
                request("GET", "http://localhost/x?datastar=%7B%22loading%22%3Atrue%7D", headers = ACCEPT)
                heard("button", "click", 0)
            }) {
                button {
                    dataIndicator("loading")
                    dataOnClick(get("/x"))
                }
            },
            dslCase("json signals", "dataJsonSignals", expect = {
                store = """{"a":1,"_b":2}"""
                // Unlike a fetch, it shows the signals that start with _.
                text("pre", "{\n  \"a\": 1,\n  \"_b\": 2\n}")
            }) {
                div {
                    dataSignals("a" to 1, "_b" to 2)
                    pre { dataJsonSignals() }
                }
            },
        )

    private val bind =
        listOf(
            dslCase(
                "bind a text input",
                "dataBind",
                key = "name",
                probe =
                    mapOf(
                        "set" to listOf(listOf("input", "value", "b")),
                        "dispatch" to listOf(listOf("input", "input")),
                        "rounds" to 1,
                    ),
                expect = {
                    listensPlain("input", "input")
                    listensPlain("input", "datastar-prop-change")
                    store = """{"name":"a"}"""
                    storeAfter = """{"name":"b"}"""
                    heard("input", "input", 0)
                },
            ) {
                input {
                    value = "a"
                    dataBind("name")
                }
            },
            dslCase("bind a dotted signal", "dataBind", key = "form.firstName", expect = {
                listensPlain("input", "input")
                listensPlain("input", "datastar-prop-change")
                store = """{"form":{"firstName":""}}"""
                heard("input", "input", 0, 0)
            }) { input { dataBind("form.firstName") } },
            dslCase(
                "bind a checkbox",
                "dataBind",
                key = "on",
                probe =
                    mapOf(
                        "set" to listOf(listOf("input", "checked", true)),
                        "dispatch" to listOf(listOf("input", "input")),
                        "rounds" to 1,
                    ),
                expect = {
                    listensPlain("input", "input")
                    listensPlain("input", "datastar-prop-change")
                    store = """{"on":false}"""
                    storeAfter = """{"on":true}"""
                    heard("input", "input", 0)
                },
            ) { input(type = InputType.checkBox) { dataBind("on") } },
            dslCase("bind radios", "dataBind", key = "r", expect = {
                listensPlain("input", "input")
                listensPlain("input", "datastar-prop-change")
                listensPlain("input2", "input")
                listensPlain("input2", "datastar-prop-change")
                store = """{"r":"b"}"""
                attr("input", "name", "r")
                attr("input2", "name", "r")
                heard("input", "input", 0, 0)
                heard("input2", "input", 0, 0)
            }) {
                input(type = InputType.radio) {
                    value = "a"
                    dataBind("r")
                }
                input(type = InputType.radio) {
                    value = "b"
                    checked = true
                    dataBind("r")
                }
            },
            dslCase("bind a select", "dataBind", key = "pick", expect = {
                listensPlain("select", "change")
                listensPlain("select", "datastar-prop-change")
                store = """{"pick":"a"}"""
                heard("select", "change", 0, 0)
            }) {
                select {
                    dataBind("pick")
                    option {
                        value = "a"
                        +"A"
                    }
                    option {
                        value = "b"
                        +"B"
                    }
                }
            },
            dslCase("bind a textarea", "dataBind", key = "note", expect = {
                listensPlain("textarea", "input")
                listensPlain("textarea", "datastar-prop-change")
                store = """{"note":"hi"}"""
                heard("textarea", "input", 0, 0)
            }) {
                textArea {
                    dataBind("note")
                    +"hi"
                }
            },
            dslCase(
                "bind a number to a property",
                "dataBind",
                key = "n",
                probe =
                    mapOf(
                        "set" to listOf(listOf("input", "value", "7")),
                        "dispatch" to listOf(listOf("input", "input")),
                        "rounds" to 1,
                    ),
                expect = {
                    listensPlain("input", "input")
                    listensPlain("input", "datastar-prop-change")
                    store = """{"n":3}"""
                    storeAfter = """{"n":7}"""
                    heard("input", "input", 0)
                },
            ) {
                input(type = InputType.number) {
                    value = "3"
                    dataBind("n") { prop = "valueAsNumber" }
                }
            },
            dslCase(
                "bind on other events",
                "dataBind",
                key = "name",
                probe =
                    mapOf(
                        "set" to listOf(listOf("input", "value", "b")),
                        "dispatch" to listOf(listOf("input", "blur")),
                        "rounds" to 1,
                    ),
                expect = {
                    listensPlain("input", "change")
                    listensPlain("input", "blur")
                    listensPlain("input", "datastar-prop-change")
                    store = """{"name":"a"}"""
                    storeAfter = """{"name":"b"}"""
                    heard("input", "blur", 0)
                },
            ) {
                input {
                    value = "a"
                    dataBind("name") { events = listOf("change", "blur") }
                }
            },
        )

    private val markup =
        listOf(
            dslCase("text", "dataText", expect = { text("div", "x") }) { div { dataText("'x'") } },
            dslCase("show false", "dataShow", expect = { attr("div", "style", "display: none;") }) { div { dataShow("false") } },
            dslCase("show true", "dataShow", expect = { attr("div", "style", null) }) { div { dataShow("true") } },
            dslCase("class keyed kebab", "dataClass", key = "is-open", expect = {
                attr("div", "class", "is-open")
            }) { div { dataClass("is-open", "true") } },
            dslCase("class keyed camelCase", "dataClass", key = "isOpen", expect = {
                attr("div", "class", "isOpen")
            }) { div { dataClass("isOpen", "true") } },
            dslCase("class keyed mixed", "dataClass", key = "foo-Bar", expect = {
                attr("div", "class", "foo-Bar")
            }) { div { dataClass("foo-Bar", "true") } },
            dslCase("class keyed with a colon", "dataClass", key = "hover:bg-red-500", expect = {
                attr("div", "class", "hover:bg-red-500")
            }) { div { dataClass("hover:bg-red-500", "true") } },
            dslCase("class keyed with case snake", "dataClass", key = "my-class", case = "snake", expect = {
                attr("div", "class", "my_class")
            }) { div { dataClass("my-class", "true", Case.SNAKE) } },
            dslCase(
                "class keyed with case kebab",
                "dataClass",
                key = "myClass",
                case = "kebab",
                expect = { attr("div", "class", "my-class") },
            ) {
                div { dataClass("myClass", "true", Case.KEBAB) }
            },
            dslCase(
                "class keyed with case pascal",
                "dataClass",
                key = "my-class",
                case = "pascal",
                expect = { attr("div", "class", "MyClass") },
            ) {
                div { dataClass("my-class", "true", Case.PASCAL) }
            },
            dslCase("class object form", "dataClass", expect = {
                attr("div", "class", "MuiButton-root")
            }) { div { dataClass("{'MuiButton-root': true}") } },
            dslCase("style keyed", "dataStyle", key = "backgroundColor", expect = {
                attr("div", "style", "background-color: red;")
            }) { div { dataStyle("backgroundColor", "'red'") } },
            dslCase("style keyed kebab", "dataStyle", key = "font-size", expect = {
                attr("div", "style", "font-size: 12px;")
            }) { div { dataStyle("font-size", "'12px'") } },
            dslCase("style custom property", "dataStyle", key = "--brand2", expect = {
                attr("div", "style", "--brand2: #10161f;")
            }) { div { dataStyle("--brand2", "'#10161f'") } },
            dslCase("style object form", "dataStyle", expect = {
                attr("div", "style", "background-color: red;")
            }) { div { dataStyle("{backgroundColor: 'red'}") } },
            dslCase("attr keyed", "dataAttr", key = "aria-label", expect = {
                attr("div", "aria-label", "hi")
            }) { div { dataAttr("aria-label", "'hi'") } },
            dslCase("attr keyed with a colon", "dataAttr", key = "xlink:href", expect = {
                attr("div", "xlink:href", "/x")
            }) { div { dataAttr("xlink:href", "'/x'") } },
            dslCase("attr object form", "dataAttr", expect = { attr("div", "title", "t") }) { div { dataAttr("{title: 't'}") } },
            dslCase(
                "attr object form on svg",
                "dataAttr",
                expect = { attr("svg", "viewBox", "0 0 1 1") },
            ) { svg { dataAttr("{viewBox: '0 0 1 1'}") } },
            dslCase("ignore", "dataIgnore", expect = { text("span", "old") }) {
                div {
                    dataIgnore()
                    span {
                        dataText("'new'")
                        +"old"
                    }
                }
            },
            dslCase("ignore self", "dataIgnore", expect = {
                text("span", "new")
                attr("div", "class", null)
            }) {
                div {
                    dataIgnore(self = true)
                    dataClass("on", "true")
                    span {
                        dataText("'new'")
                        +"old"
                    }
                }
            },
            dslCase("ignore morph", "dataIgnoreMorph", patch = {
                div {
                    id = "m"
                    dataIgnoreMorph()
                    +"new"
                }
            }, expect = {
                text("m", "old")
            }) {
                div {
                    id = "m"
                    dataIgnoreMorph()
                    +"old"
                }
            },
            dslCase("preserve attr", "dataPreserveAttr", patch = {
                details {
                    id = "d"
                    dataPreserveAttr("open", "class")
                }
            }, expect = {
                attr("d", "open", "")
                attr("d", "class", "x")
            }) {
                details {
                    id = "d"
                    attributes["open"] = ""
                    attributes["class"] = "x"
                    dataPreserveAttr("open", "class")
                }
            },
            dslCase("nonce", "dataNonce", expect = {
                // The client reads the nonce once and takes it off the page, then compiles under it.
                attr("html", "data-nonce", null)
                text("div", "2")
            }) {
                html {
                    dataNonce("abc")
                    body { div { dataText("1 + 1") } }
                }
            },
        )

    private val actions =
        listOf(
            dslCase("get", "get", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                listensPlain("document", "visibilitychange")
                store = """{"a":1}"""
                request("GET", "http://localhost/x?datastar=%7B%22a%22%3A1%7D", headers = ACCEPT)
                heard("button", "click", 0)
            }) {
                button {
                    dataSignals("a", "1")
                    dataOnClick(get("/x"))
                }
            },
            dslCase("post", "post", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                store = """{"a":1}"""
                request("POST", "http://localhost/x", """{"a":1}""", JSON_BODY)
                heard("button", "click", 0)
            }) {
                button {
                    dataSignals("a", "1")
                    dataOnClick(post("/x"))
                }
            },
            dslCase("put", "put", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                request("PUT", "http://localhost/x", "{}", JSON_BODY)
                heard("button", "click", 0)
            }) { button { dataOnClick(put("/x")) } },
            dslCase("patch", "patch", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                request("PATCH", "http://localhost/x", "{}", JSON_BODY)
                heard("button", "click", 0)
            }) { button { dataOnClick(patch("/x")) } },
            dslCase("delete", "delete", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                request("DELETE", "http://localhost/x?datastar=%7B%7D", headers = ACCEPT)
                heard("button", "click", 0)
            }) { button { dataOnClick(delete("/x")) } },
            dslCase("query", "query", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                request("QUERY", "http://localhost/x", "{}", JSON_BODY)
                heard("button", "click", 0)
            }) { button { dataOnClick(query("/x")) } },
            dslCase("get a uri with a query and quotes", "get", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                listensPlain("document", "visibilitychange")
                // The client rebuilds the query with URLSearchParams, which encodes a space as +.
                request("GET", "http://localhost/x?q=it%27s+%22a%22&datastar=%7B%7D", headers = ACCEPT)
                heard("button", "click", 0)
            }) { button { dataOnClick(get("/x?q=it's \"a\"")) } },
            dslCase("post as a form", "post", probe = mapOf("rounds" to 1, "dispatch" to listOf(listOf("button", "click"))), expect = {
                listens("button", "click")
                listensPlain("form", "submit")
                request("POST", "http://localhost/x", "q=z", ACCEPT + ("Content-Type" to "application/x-www-form-urlencoded"))
                heard("button", "click", 0)
            }) {
                form {
                    input {
                        name = "q"
                        value = "z"
                    }
                    button(type = ButtonType.button) {
                        dataOnClick(
                            post("/x") {
                                contentType =
                                    FetchOptions.ContentType.FORM
                            },
                        )
                    }
                }
            },
            dslCase("post a form found by selector", "post", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                listensPlain("f", "submit")
                request("POST", "http://localhost/x", "q=y", ACCEPT + ("Content-Type" to "application/x-www-form-urlencoded"))
                heard("button", "click", 0)
            }) {
                form {
                    id = "f"
                    input {
                        name = "q"
                        value = "y"
                    }
                }
                button {
                    dataOnClick(
                        post("/x") {
                            contentType = FetchOptions.ContentType.FORM
                            selector = "#f"
                        },
                    )
                }
            },
            dslCase("post filtered signals", "post", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                store = """{"a":1,"b":2,"_c":3}"""
                request("POST", "http://localhost/x", """{"b":2}""", JSON_BODY)
                heard("button", "click", 0)
            }) {
                button {
                    dataSignals("a" to 1, "b" to 2, "_c" to 3)
                    dataOnClick(
                        post("/x") {
                            filterSignals =
                                SignalFilter(include = Regex("^b$"))
                        },
                    )
                }
            },
            dslCase("post filtered by an exclude with a slash", "post", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                store = """{"a/b":1,"c":2}"""
                request("POST", "http://localhost/x", """{"c":2}""", JSON_BODY)
                heard("button", "click", 0)
            }) {
                button {
                    dataSignals("{'a/b': 1, c: 2}")
                    dataOnClick(post("/x") { filterSignals = SignalFilter(exclude = Regex("a/b")) })
                }
            },
            dslCase("post with headers", "post", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                request("POST", "http://localhost/x", "{}", JSON_BODY + ("X-Csrf" to "t'k\"n"))
                heard("button", "click", 0)
            }) { button { dataOnClick(post("/x") { headers = mapOf("X-Csrf" to "t'k\"n") }) } },
            dslCase("post with a payload", "post", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                request("POST", "http://localhost/x", """{"id":7,"tags":["a"],"s":"it's"}""", JSON_BODY)
                heard("button", "click", 0)
            }) { button { dataOnClick(post("/x") { payload = mapOf("id" to 7, "tags" to listOf("a"), "s" to "it's") }) } },
            dslCase("post with a payload expression", "post", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                store = """{"sel":3}"""
                request("POST", "http://localhost/x", """{"id":3}""", JSON_BODY)
                heard("button", "click", 0)
            }) {
                button {
                    dataSignals("sel", "3")
                    dataOnClick(post("/x") { payloadExpr = "{id: ${'$'}sel}" })
                }
            },
            dslCase("get open when hidden", "get", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                request("GET", "http://localhost/x?datastar=%7B%7D", headers = ACCEPT)
                heard("button", "click", 0)
            }) { button { dataOnClick(get("/x") { openWhenHidden = true }) } },
            dslCase("post closed when hidden", "post", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                listensPlain("document", "visibilitychange")
                request("POST", "http://localhost/x", "{}", JSON_BODY)
                heard("button", "click", 0)
            }) { button { dataOnClick(post("/x") { openWhenHidden = false }) } },
            dslCase("get retried on error", "get", probe = mapOf("rounds" to 1, "respond" to mapOf("status" to 500)), expect = {
                listens("button", "click")
                listensPlain("document", "visibilitychange")
                timer("setTimeout", 100)
                timer("setTimeout", 200)
                timer("setTimeout", 300)
                repeat(4) { request("GET", "http://localhost/x?datastar=%7B%7D", headers = ACCEPT) }
                errors += "FetchFailed"
                heard("button", "click", 0)
            }) {
                button {
                    dataOnClick(
                        get("/x") {
                            retry = FetchOptions.Retry.ERROR
                            retryInterval = 100.milliseconds
                            retryScaler = 2.0
                            retryMaxWait = 300.milliseconds
                            retryMaxCount = 3
                        },
                    )
                }
            },
            dslCase("get never retried", "get", probe = mapOf("rounds" to 1, "respond" to mapOf("status" to 500)), expect = {
                listens("button", "click")
                listensPlain("document", "visibilitychange")
                request("GET", "http://localhost/x?datastar=%7B%7D", headers = ACCEPT)
                heard("button", "click", 0)
            }) { button { dataOnClick(get("/x") { retry = FetchOptions.Retry.NEVER }) } },
            dslCase("get retried always", "get", probe = mapOf("rounds" to 1, "respond" to mapOf("status" to 500)), expect = {
                listens("button", "click")
                listensPlain("document", "visibilitychange")
                timer("setTimeout", 1000)
                repeat(2) { request("GET", "http://localhost/x?datastar=%7B%7D", headers = ACCEPT) }
                errors += "FetchFailed"
                heard("button", "click", 0)
            }) {
                button {
                    dataOnClick(
                        get("/x") {
                            retry = FetchOptions.Retry.ALWAYS
                            retryMaxCount = 1
                        },
                    )
                }
            },
            dslCase(
                "post with response overrides the client does not read",
                "post",
                probe =
                    mapOf(
                        "rounds" to 1,
                        "respond" to
                            mapOf("status" to 200, "headers" to mapOf("Content-Type" to "text/html"), "body" to "<div id=\"t\">new</div>"),
                    ),
                expect = {
                    listens("button", "click")
                    request("POST", "http://localhost/x", "{}", JSON_BODY)
                    heard("button", "click", 0)
                    // Datastar 1.0.4's fetch never takes responseOverrides from the options, so the
                    // element is morphed by its id, as the headers alone say.
                    text("t", "new")
                },
            ) {
                div {
                    id = "t"
                    +"old"
                }
                div {
                    id = "other"
                    +"other"
                }
                button {
                    dataOnClick(
                        post("/x") {
                            responseOverrides {
                                selector = "#other"
                                mode =
                                    io.github.markusaugust.streamlord.core.domain.ElementPatchMode.INNER
                            }
                        },
                    )
                }
            },
            dslCase("get with request cancellation disabled", "get", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                listensPlain("document", "visibilitychange")
                request("GET", "http://localhost/x?datastar=%7B%7D", headers = ACCEPT)
                heard("button", "click", 0)
            }) { button { dataOnClick(get("/x") { requestCancellation = FetchOptions.RequestCancellation.DISABLED }) } },
            dslCase("set all", "setAll", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                store = """{"a":1,"b":2,"c":3}"""
                storeAfter = """{"a":0,"b":0,"c":3}"""
                heard("button", "click", 0)
            }) {
                button {
                    dataSignals("a" to 1, "b" to 2, "c" to 3)
                    dataOnClick(setAll(0, SignalFilter(exclude = Regex("^c$"))))
                }
            },
            dslCase("toggle all", "toggleAll", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                store = """{"a":false,"b":true}"""
                storeAfter = """{"a":true,"b":false}"""
                heard("button", "click", 0)
            }) {
                button {
                    dataSignals("a" to false, "b" to true)
                    dataOnClick(toggleAll())
                }
            },
            dslCase("peek", "peek", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                store = """{"a":4}"""
                heard("button", "click", 1)
                probes = listOf(listOf(4))
            }) {
                button {
                    dataSignals("a", "4")
                    dataOnClick("probe(${peek(signal("a"))})")
                }
            },
            dslCase("expression helpers", "statements", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                store = """{"n":1,"m":5,"on":false,"s":"","t":0}"""
                storeAfter = """{"n":2,"m":4,"on":true,"s":"it's","t":2}"""
                heard("button", "click", 1)
                probes = listOf(listOf(false))
            }) {
                button {
                    dataSignals("n" to 1, "m" to 5, "on" to false, "s" to "", "t" to 0)
                    dataOnClick(
                        statements(
                            increment("n"),
                            decrement("m"),
                            toggle("on"),
                            set("s", "it's"),
                            setExpr("t", "${signal("n")}"),
                            "probe(${not("on")})",
                        ),
                    )
                }
            },
            dslCase("a JavaScript string from js()", "js", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                heard("button", "click", 1)
                probes = listOf(listOf("a'b\"c\\d</script> "))
            }) { button { dataOnClick("probe(${js("a'b\"c\\d</script> ")})") } },
        )

    private val values =
        listOf(
            dslCase("an expression with quotes and markup", "dataText", expect = {
                text("div", "a\"b'c<d>&e")
            }) { div { dataText("'a\"b\\'c<d>&e'") } },
            dslCase("an expression with a character reference in a string", "dataText", expect = {
                text("div", "&amp;")
            }) { div { dataText("'&amp;'") } },
            dslCase("an expression with a backslash before an ampersand", "dataText", expect = {
                text("div", "&amp;")
            }) { div { dataText("'\\&amp;'") } },
            dslCase("an expression with a line break", "dataText", expect = { text("div", "a\nb") }) { div { dataText("'a\\nb'\n") } },
            dslCase("an expression with a no-break space", "dataText", expect = { text("div", "a b") }) { div { dataText("'a b'") } },
            dslCase("an expression ending in a backslash in a regex", "dataText", expect = {
                text("div", "true")
            }) { div { dataText("/\\\\/.test('\\\\')") } },
            dslCase("an expression with a script end in a string", "dataText", expect = {
                text("div", "</script>")
            }) { div { dataText("'</script>'") } },
        )

    private val aliased =
        listOf(
            dslCase("aliased signals and on", "dataSignals", prefix = "data-star-", expect = {
                listens("button", "click")
                store = """{"fooBar":1}"""
                heard("button", "click", 1, 1)
            }) {
                button {
                    dataSignals("fooBar", "1")
                    dataOnClick("probe(evt)")
                }
            },
            dslCase("aliased ignore", "dataIgnore", prefix = "data-star-", expect = { text("span", "old") }) {
                div {
                    dataIgnore()
                    span {
                        dataText("'new'")
                        +"old"
                    }
                }
            },
            dslCase("aliased preserve attr", "dataPreserveAttr", prefix = "data-star-", patch = {
                details {
                    id = "d"
                    dataPreserveAttr("open")
                }
            }, expect = {
                attr("d", "open", "")
            }) {
                details {
                    id = "d"
                    attributes["open"] = ""
                    dataPreserveAttr("open")
                }
            },
            dslCase("aliased json signals", "dataJsonSignals", prefix = "data-star-", expect = {
                store = """{"a":1}"""
                text("pre", "{\"a\":1}")
            }) {
                div {
                    dataSignals("a" to 1)
                    pre { dataJsonSignals(terse = true) }
                }
            },
            dslCase(
                "aliased on signal patch filtered",
                "dataOnSignalPatch",
                prefix = "data-star-",
                probe =
                    mapOf("eval" to "Datastar.mergePatch({a: 2}); Datastar.mergePatch({b: 3})"),
                expect = {
                    listensPlain("document", "datastar-signal-patch")
                    store = """{"a":1,"b":1}"""
                    storeAfter = """{"a":2,"b":3}"""
                    probes = listOf(listOf(mapOf("a" to 2)))
                },
            ) {
                div {
                    dataSignals("a" to 1, "b" to 1)
                    dataOnSignalPatch("probe(patch)", SignalFilter(include = Regex("^a$")))
                }
            },
            dslCase("json signals filtered and terse", "dataJsonSignals", expect = {
                store = """{"a":1,"b":2}"""
                text("pre", "{\"b\":2}")
            }) {
                div {
                    dataSignals("a" to 1, "b" to 2)
                    pre { dataJsonSignals(SignalFilter(include = Regex("^b$")), terse = true) }
                }
            },
            dslCase(
                "an svg element",
                "dataClass",
                key = "isOn",
                expect = { attr("svg", "class", "isOn") },
            ) { svg { dataClass("isOn", "true") } },
        )

    @Test
    fun `the client reads what the DSL means`() =
        fixture.check(events + timing + observers + signals + bind + markup + actions + values + aliased)

    @Test
    fun `the cases reach every entry of the catalog`() =
        fixture.checkCoverage(
            pro = false,
            exempt =
                buildMap {
                    for (attribute in listOf("bind", "ref", "indicator")) {
                        val why = "the DSL writes the signal of data-$attribute in the value, so it writes no key and no __case"
                        put("$attribute keyed", why)
                        put("$attribute __case", why)
                        for (case in listOf("camel", "kebab", "snake", "pascal")) put("$attribute __case.$case", why)
                    }
                    val picked =
                        "the DSL picks the __case of a signal name: none for a camelCase name, pascal for one that starts with a capital"
                    for (case in listOf("camel", "kebab", "snake")) {
                        put("signals __case.$case", picked)
                        put("computed __case.$case", picked)
                    }
                },
        )
}
