package io.github.markusaugust.streamlord.html.pro

import io.github.markusaugust.streamlord.html.SignalFilter
import io.github.markusaugust.streamlord.html.dataOnClick
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.input
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the free Datastar engine splits what the Pro DSL writes, `conformance/dsl/pro.json`. The
 * free client holds no Pro plugin, so `dsl.mjs` registers one for each Pro name that records the
 * key, the modifiers and the value it is handed, and one for each Pro action that records its
 * arguments. What Pro then does with them is not read: nothing from Datastar Pro is.
 */
class ProDslAsTheClientReadsItTest {
    private val fixture =
        ClientReadings(
            "pro.json",
            "How the free Datastar engine split the attributes and action calls the Pro DSL writes: each case's name, call, prefix, " +
                "probe and html come from ProDslAsTheClientReadsItTest under ./gradlew :streamlord-html-pro:updateClientReadings, its " +
                "read from conformance/generate/dsl.mjs, which registers a recording plugin under each Pro name in the free bundle. " +
                "Datastar Pro itself is not read, so what Pro does with a key, such as the casing the catalog gives it, is not held here.",
            proAttributes =
                listOf(
                    "animate",
                    "custom-validity",
                    "match-media",
                    "on-raf",
                    "on-resize",
                    "persist",
                    "query-string",
                    "replace-url",
                    "scroll-into-view",
                    "view-transition",
                ),
            proActions = listOf("clipboard", "fit", "intl"),
        )

    /** What the recording plugin for [name] is handed: its key, its modifiers with their tags, and its value. */
    private fun Expect.pro(
        name: String,
        key: String?,
        value: String,
        vararg modifiers: Pair<String, List<String>>,
    ) {
        probes = (probes ?: emptyList()) + listOf(listOf("pro", name, key, modifiers.toMap(), value))
    }

    private val attributes =
        listOf(
            dslCase(
                "animate",
                "dataAnimate",
                expect = { pro("animate", null, "{opacity: '1'}") },
            ) { div { dataAnimate("{opacity: '1'}") } },
            dslCase("animate keyed with every modifier", "dataAnimate", key = "opacity", expect = {
                pro(
                    "animate",
                    "opacity",
                    "${'$'}o",
                    "duration" to listOf("500ms"),
                    "delay" to listOf("100ms"),
                    "ease" to listOf("inoutquad"),
                    "loop" to emptyList(),
                    "pingpong" to emptyList(),
                )
            }) {
                div {
                    dataAnimate("opacity", "${'$'}o") {
                        duration = 500.milliseconds
                        delay = 100.milliseconds
                        ease = Easing.IN_OUT_QUAD
                        loop = true
                        pingpong = true
                    }
                }
            },
            dslCase("animate with each easing", "dataAnimate", expect = {
                for (easing in Easing.entries) pro("animate", null, "{r: '1'}", "ease" to listOf(easing.name.lowercase().replace("_", "")))
            }) { for (easing in Easing.entries) div { dataAnimate("{r: '1'}") { ease = easing } } },
            dslCase("custom validity", "dataCustomValidity", expect = { pro("custom-validity", null, "${'$'}ok ? '' : 'no'") }) {
                input { dataCustomValidity("${'$'}ok ? '' : 'no'") }
            },
            dslCase("match media", "dataMatchMedia", key = "isDark", expect = {
                // The catalog reads the key as camelCase, so Pro would store isDark.
                pro("match-media", "is-dark", "'(prefers-color-scheme: dark)'")
            }) { div { dataMatchMedia("isDark", "(prefers-color-scheme: dark)") } },
            dslCase("match media PascalCase", "dataMatchMedia", key = "IsDark", expect = {
                pro("match-media", "is-dark", "'(min-width: 1px)'", "case" to listOf("pascal"))
            }) { div { dataMatchMedia("IsDark", "(min-width: 1px)") } },
            dslCase("on raf", "dataOnRaf", expect = { pro("on-raf", null, "probe(1)") }) { div { dataOnRaf("probe(1)") } },
            dslCase("on raf with timing", "dataOnRaf", expect = {
                pro(
                    "on-raf",
                    null,
                    "probe(1)",
                    "delay" to listOf("10ms"),
                    "debounce" to listOf("20ms", "leading", "notrailing"),
                    "throttle" to listOf("30ms", "noleading", "trailing"),
                    "viewtransition" to emptyList(),
                )
            }) {
                div {
                    dataOnRaf("probe(1)") {
                        delay = 10.milliseconds
                        debounce = 20.milliseconds
                        debounceLeading = true
                        debounceNoTrailing = true
                        throttle = 30.milliseconds
                        throttleNoLeading = true
                        throttleTrailing = true
                        viewTransition = true
                    }
                }
            },
            dslCase("on resize", "dataOnResize", expect = { pro("on-resize", null, "probe(1)") }) { div { dataOnResize("probe(1)") } },
            dslCase("on resize with timing", "dataOnResize", expect = {
                pro(
                    "on-resize",
                    null,
                    "probe(1)",
                    "delay" to listOf("1000ms"),
                    "debounce" to listOf("200ms", "leading", "notrailing"),
                    "throttle" to listOf("300ms", "noleading", "trailing"),
                    "viewtransition" to emptyList(),
                )
            }) {
                div {
                    dataOnResize("probe(1)") {
                        delay = 1.seconds
                        debounce = 200.milliseconds
                        debounceLeading = true
                        debounceNoTrailing = true
                        throttle = 300.milliseconds
                        throttleNoLeading = true
                        throttleTrailing = true
                        viewTransition = true
                    }
                }
            },
            dslCase("persist", "dataPersist", expect = { pro("persist", null, "") }) { div { dataPersist() } },
            dslCase("persist keyed in the session", "dataPersist", key = "myForm", expect = {
                pro("persist", "my-form", "{include: /^form\\./}", "session" to emptyList())
            }) { div { dataPersist("myForm", SignalFilter(include = Regex("^form\\.")), session = true) } },
            dslCase("query string", "dataQueryString", expect = { pro("query-string", null, "") }) { div { dataQueryString() } },
            dslCase("query string filtered with history", "dataQueryString", expect = {
                pro("query-string", null, "{exclude: /^_/}", "filter" to emptyList(), "history" to emptyList())
            }) { div { dataQueryString(SignalFilter(exclude = Regex("^_")), omitEmpty = true, history = true) } },
            dslCase("replace url", "dataReplaceUrl", expect = { pro("replace-url", null, "`/page/${'$'}{${'$'}n}`") }) {
                div { dataReplaceUrl("`/page/${'$'}{${'$'}n}`") }
            },
            dslCase(
                "scroll into view",
                "dataScrollIntoView",
                expect = { pro("scroll-into-view", null, "") },
            ) { div { dataScrollIntoView() } },
            dslCase("scroll into view with every option", "dataScrollIntoView", expect = {
                for (behavior in ScrollBehavior.entries) {
                    pro("scroll-into-view", null, "", behavior.name.lowercase() to emptyList())
                }
                for (align in HorizontalAlign.entries) pro("scroll-into-view", null, "", "h${align.name.lowercase()}" to emptyList())
                for (align in VerticalAlign.entries) pro("scroll-into-view", null, "", "v${align.name.lowercase()}" to emptyList())
                pro(
                    "scroll-into-view",
                    null,
                    "",
                    "smooth" to emptyList(),
                    "hcenter" to emptyList(),
                    "vend" to emptyList(),
                    "focus" to emptyList(),
                )
            }) {
                for (behavior in ScrollBehavior.entries) div { dataScrollIntoView(behavior = behavior) }
                for (align in HorizontalAlign.entries) div { dataScrollIntoView(horizontal = align) }
                for (align in VerticalAlign.entries) div { dataScrollIntoView(vertical = align) }
                div { dataScrollIntoView(ScrollBehavior.SMOOTH, HorizontalAlign.CENTER, VerticalAlign.END, focus = true) }
            },
            dslCase("view transition", "dataViewTransition", expect = { pro("view-transition", null, "'card-' + ${'$'}id") }) {
                div { dataViewTransition("'card-' + ${'$'}id") }
            },
        )

    private val actions =
        listOf(
            dslCase("clipboard", "clipboard", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                heard("button", "click", 1)
                probes = listOf(listOf("pro-action", "clipboard", "it's \"x\""))
            }) { button { dataOnClick(clipboard("it's \"x\"")) } },
            dslCase("clipboard base64 from an expression", "clipboardExpr", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                heard("button", "click", 1)
                probes = listOf(listOf("pro-action", "clipboard", "aGk=", true))
            }) { button { dataOnClick(clipboardExpr("'aGk='", isBase64 = true)) } },
            dslCase("fit", "fit", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                heard("button", "click", 1)
                probes = listOf(listOf("pro-action", "fit", 5, 0, 10, 0, 1.5, true, true))
            }) { button { dataOnClick(fit("5", 0, 10, 0, 1.5, clamp = true, round = true)) } },
            dslCase("intl", "intl", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                heard("button", "click", 1)
                probes = listOf(listOf("pro-action", "intl", "number", 3, mapOf("style" to "currency", "currency" to "NOK"), "nb-NO"))
            }) { button { dataOnClick(intl(IntlType.NUMBER, "3", mapOf("style" to "currency", "currency" to "NOK"), "nb-NO")) } },
            dslCase("intl with several locales", "intl", probe = mapOf("rounds" to 1), expect = {
                listens("button", "click")
                heard("button", "click", 1)
                probes = listOf(listOf("pro-action", "intl", "relativeTime", -1, emptyMap<String, Any?>(), listOf("nb", "en")))
            }) { button { dataOnClick(intl(IntlType.RELATIVE_TIME, "-1", emptyMap(), "nb", "en")) } },
            dslCase("a Pro attribute in a form", "dataCustomValidity", expect = { pro("custom-validity", null, "''") }) {
                form { input { dataCustomValidity("''") } }
            },
        )

    @Test
    fun `the free engine splits what the Pro DSL writes as it means`() = fixture.check(attributes + actions)

    @Test
    fun `the cases reach every entry of the catalog`() =
        fixture.checkCoverage(
            pro = true,
            exempt =
                listOf("camel", "kebab", "snake").associate {
                    "match-media __case.$it" to
                        "the DSL picks the __case of a signal name: none for a camelCase name, pascal for one that starts with a capital"
                },
        )
}
