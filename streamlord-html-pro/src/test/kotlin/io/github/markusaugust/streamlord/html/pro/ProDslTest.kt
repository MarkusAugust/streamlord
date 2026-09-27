package io.github.markusaugust.streamlord.html.pro

import io.github.markusaugust.streamlord.html.Case
import io.github.markusaugust.streamlord.html.DatastarAttributes
import io.github.markusaugust.streamlord.html.SignalFilter
import io.github.markusaugust.streamlord.html.elements
import io.github.markusaugust.streamlord.html.signal
import kotlinx.html.div
import kotlinx.html.input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ProDslTest {

    @Test
    fun `pro attributes render their documented names and modifiers`() {
        val html = elements {
            div {
                dataAnimate("r", "${'$'}size * 2")
                dataMatchMedia("is-dark", "prefers-color-scheme: dark", case = Case.CAMEL)
                dataOnRaf("${'$'}t++") { throttle = 16.milliseconds; throttleTrailing = true }
                dataOnResize("${'$'}w = el.clientWidth") { debounce = 100.milliseconds; debounceLeading = true }
                dataPersist()
                dataPersist("draft", SignalFilter.include("^form"), session = true)
                dataQueryString(SignalFilter.exclude("^_"), omitEmpty = true, history = true)
                dataReplaceUrl("`/page${'$'}{${'$'}page}`")
                dataScrollIntoView(ScrollBehavior.SMOOTH, HorizontalAlign.CENTER, VerticalAlign.START, focus = true)
                dataViewTransition(signal("name"))
            }
            input { dataCustomValidity("${'$'}a === ${'$'}b ? '' : 'Values must match.'") }
        }
        assertEquals(
            """<div data-animate:r="${'$'}size * 2" data-match-media:is-dark__case.camel="'prefers-color-scheme: dark'" """ +
                """data-on-raf__throttle.16ms.trailing="${'$'}t++" data-on-resize__debounce.100ms.leading="${'$'}w = el.clientWidth" """ +
                """data-persist="" data-persist:draft__session="{include: /^form/}" """ +
                """data-query-string__filter__history="{exclude: /^_/}" data-replace-url="`/page${'$'}{${'$'}page}`" """ +
                """data-scroll-into-view__smooth__hcenter__vstart__focus="" data-view-transition="${'$'}name"></div>""" +
                """<input data-custom-validity="${'$'}a === ${'$'}b ? '' : 'Values must match.'">""",
            html,
        )
    }

    @Test
    fun `persist writes its key in kebab-case like the other raw keys`() {
        assertEquals("""<div data-persist:my-key=""></div>""", elements { div { dataPersist("myKey") } })
    }

    @Test
    fun `pro attributes honour the aliased prefix`() {
        DatastarAttributes.prefix = "data-star-"
        try {
            assertEquals(
                """<div data-star-persist:k="" data-star-query-string__history="" data-star-scroll-into-view__smooth="" data-star-on-raf="x()"></div>""",
                elements { div { dataPersist("k"); dataQueryString(history = true); dataScrollIntoView(ScrollBehavior.SMOOTH); dataOnRaf("x()") } },
            )
        } finally {
            DatastarAttributes.prefix = "data-"
        }
    }

    @Test
    fun `pro actions`() {
        assertEquals("""@clipboard("Hello, world!")""", clipboard("Hello, world!"))
        assertEquals("""@clipboard("SGVsbG8=", true)""", clipboard("SGVsbG8=", isBase64 = true))
        assertEquals("@clipboard(${'$'}code)", clipboardExpr(signal("code")))
        assertEquals("@fit(${'$'}sliderValue, 0, 100, 0, 255)", fit(signal("sliderValue"), 0, 100, 0, 255))
        assertEquals("@fit(${'$'}x, 0, 1, 0, 1, true)", fit(signal("x"), 0, 1, 0, 1, clamp = true))
        assertEquals("@fit(${'$'}x, 0, 1, 0, 1, false, true)", fit(signal("x"), 0, 1, 0, 1, round = true))
        assertEquals("""@intl("number", 1000000, {"style":"currency","currency":"USD"})""", intl(IntlType.NUMBER, "1000000", mapOf("style" to "currency", "currency" to "USD")))
        assertEquals("""@intl("datetime", new Date(), {}, "de-AT")""", intl(IntlType.DATETIME, "new Date()", locales = arrayOf("de-AT")))
        assertEquals("""@intl("list", ${'$'}items, {"type":"conjunction"}, ["nb","en"])""", intl(IntlType.LIST, signal("items"), mapOf("type" to "conjunction"), "nb", "en"))
    }
}
