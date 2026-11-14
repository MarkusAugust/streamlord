package demo

import io.github.markusaugust.streamlord.core.domain.interpolate
import io.github.markusaugust.streamlord.html.*
import kotlinx.html.FlowContent
import kotlinx.html.div
import org.intellij.lang.annotations.Language

// A $$ raw string that opens on the next line: $$hsTotal and $${hsId} are Kotlin's templates.
fun hsCounter(hsTotal: Int, hsId: Int): String =
    $$"""
    <div id="hs-counter" data-signals="{hsCount: $$hsTotal, hsKey: $${hsId}}">
        <button data-on:click="$hsCount++">Raise</button>
    </div>
    """.trimIndent()

// Named arguments of a call that takes two.
fun FlowContent.hsNamed() {
    div { dataOn(event = "input", expression = "\$hsQuery = el.value") }
    div { dataOn(expression = "\$hsOpen = !\$hsOpen", event = "click") }
    div { dataSignals(name = "hsFlag", expression = "true") }
    div { dataClass(name = "hidden", expression = "!\$hsOpen") }
}

// A marked double-quoted literal and interpolate's format, however they open.
@Language("HTML")
val hsGreeting = "Hello <b data-text=\"\$hsName\"></b>"

fun hsTitled(title: String) = interpolate("Hello <b data-text=\"\$hsName\" data-attr:title=\"%s\"></b>", title)

// Ordinary attributes and JSON in escaped quotes in a plain string.
fun hsButton() = "<div class=\"hs-btn\" id=\"hs-save\" data-signals='{\"hsClicks\": 0}' data-text=\"\$hsClicks\"></div>"

// A margin before each line, and an escaped dollar in a JavaScript template.
val hsMargin =
    """
    |<div data-show="${'$'}hsOpen">
    |  <p data-text="`x ${'$'}{${'$'}hsClicks}`"></p>
    |</div>
    """.trimMargin()

// The format on a line of its own, as ktlint wraps it, and a marked literal joined to more.
fun hsWrapped(title: String) =
    interpolate(
        "Hi <b data-text=\"\$hsName\" data-attr:title=\"%s\"></b>",
        title,
    )

@Language("HTML")
val hsJoined = "Hi <b data-text=\"\$hsName\"></b>" + hsGreeting
