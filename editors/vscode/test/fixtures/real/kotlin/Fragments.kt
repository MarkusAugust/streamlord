package forge.fragments

import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.core.domain.interpolate
import org.intellij.lang.annotations.Language

const val CARD_ID = "card"

val plain = "<button id=\"buy\" data-on:click=\"@post('/buy')\" data-indicator:buying>Buy</button>"

val raw = """<div id="total" data-signals:gold="12" data-text="${'$'}gold + ' gold'"></div>"""

val trimmed =
    """
    <section id="inventory" data-signals="{slots: 8, used: 3}">
      <p data-text="${'$'}used + ' of ' + ${'$'}slots"></p>
      <button data-on:click__debounce.200ms="@get('/inventory', {retry: 'never'})">Refresh</button>
    </section>
    """.trimIndent()

val dollars = $$"""<p id="free" data-show="$slots > $used" data-class:full="$slots === $used">Room left</p>"""

val dollarsTemplate = $$"""<p id="who" data-text="`${$heroName} carries ${$used}`"></p>"""

@Language("HTML")
val annotated = "<span id=\"gold\" data-text=\"\$gold\"></span>"

@Language("HTML")
fun card(title: String, price: Int): String =
    """
    <article id="$CARD_ID" data-signals:price="$price">
      <h2>${title.escaped()}</h2>
      <p data-text="${'$'}price * 2"></p>
    </article>
    """.trimIndent()

fun safeCard(title: String, price: Int): String =
    interpolate(
        """<article id="card-%s"><h2>%s</h2><p data-signals="{price: %s}" data-text="${'$'}price"></p></article>""",
        price,
        title,
        price,
    )

fun trustedCard(body: Trusted, nonce: String): String = interpolate("<html data-nonce=\"%s\"><body>%s</body></html>", nonce, body)

fun rows(names: List<String>): String = names.joinToString("") { interpolate("<li>%s</li>", it) }

val notMarkup = "gold: $CARD_ID, 1 < 2"
