package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.html.dataNonce
import io.github.markusaugust.streamlord.html.dataOnClick
import io.github.markusaugust.streamlord.html.dataSignals
import io.github.markusaugust.streamlord.html.dataText
import io.github.markusaugust.streamlord.html.get
import io.github.markusaugust.streamlord.html.signal
import kotlinx.html.ButtonType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.lang
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.script
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.title

/** The Datastar client, from the same place the documentation's own pages take it. */
private const val DATASTAR = "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.4/bundles/datastar.js"

/**
 * The policy this page is served under.
 *
 * Three sources beyond the starter policy, each for a reason the page can point at. `script-src`
 * carries the nonce rather than `'self'` alone, because the client comes from jsdelivr and an
 * external script is admitted by a nonce like any other. `style-src` carries it so the one
 * `<style>` element below is allowed; `'self'` by itself blocks an inline one. `connect-src` is
 * the origin, which is where the stream the button opens comes from.
 *
 * No `'unsafe-eval'` and no `'unsafe-inline'` anywhere, which is the point of the page: Datastar
 * compiles its expressions through nonce-bearing script elements instead.
 */
internal fun policy(nonce: String): String =
    "default-src 'self'; " +
        "script-src 'nonce-$nonce'; " +
        "style-src 'nonce-$nonce'; " +
        "connect-src 'self'; " +
        "img-src 'self'; " +
        "object-src 'none'; " +
        "base-uri 'self'"

// No child or sibling combinators, so the rules pass through kotlinx.html's text escaping
// unchanged. A `>` here would arrive at the browser as `&gt;` and match nothing.
private val CSS =
    """
    :root { color-scheme: dark; }
    body {
      margin: 0;
      padding: 3rem 1.5rem;
      background: #0e0f13;
      color: #e8e6e3;
      font: 16px/1.6 ui-sans-serif, system-ui, sans-serif;
    }
    main { max-width: 42rem; margin: 0 auto; }
    h1 { font-size: 1.6rem; line-height: 1.2; }
    pre {
      padding: 1rem;
      overflow-x: auto;
      border: 1px solid #2a2d36;
      border-radius: 4px;
      background: #15171d;
      font-size: 0.8rem;
    }
    button {
      padding: 0.6rem 1.1rem;
      border: 1px solid #4c5160;
      border-radius: 4px;
      background: #1d2029;
      color: inherit;
      font: inherit;
      cursor: pointer;
    }
    .counter__number { margin: 1rem 0 0; font-size: 2.4rem; font-variant-numeric: tabular-nums; }
    .counter__state { margin: 0; color: #a7a39d; font-size: 0.9rem; }
    """.trimIndent()

/**
 * A page that really runs under a Content Security Policy, served by this service so the nonce
 * is generated per response rather than written into a file.
 *
 * Everything on it is a Datastar expression: the button's `data-on:click`, its `data-text`, the
 * signals on `<main>`. Under this policy none of them can be compiled with `new Function`, so if
 * the button counts, CSP mode is working and the nonce in `<html>` matched the one in the header.
 *
 * @param nonce This response's nonce, from `call.cspNonce`.
 */
internal fun cspPage(nonce: String): String =
    "<!doctype html>\n" +
        createHTML().html {
            lang = "en"
            dataNonce(nonce)

            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title("CSP mode | streamlord-live")

                script(type = "module", src = DATASTAR) {
                    attributes["nonce"] = nonce
                }
                style {
                    attributes["nonce"] = nonce
                    +CSS
                }
            }

            body {
                main {
                    dataSignals("running" to false, "count" to 0)

                    h1 { +"Datastar in CSP mode" }

                    p {
                        +(
                            "This page is served by streamlord-live with a nonce from " +
                                "CspNoncePlugin. The same value is in the data-nonce attribute on " +
                                "<html> and in the Content-Security-Policy header below, because " +
                                "the plugin writes both from one variable."
                        )
                    }

                    pre { code { +policy(nonce) } }

                    p {
                        +(
                            "The policy allows no unsafe-eval and no unsafe-inline. If the counter " +
                                "counts, every expression on this page was compiled through a " +
                                "nonce-bearing script element."
                        )
                    }

                    div {
                        button {
                            type = ButtonType.button
                            dataOnClick(get("/counter"))
                            dataText("${signal("running")} ? 'Stop' : 'Start counting'")
                        }

                        div {
                            id = "counter"
                            attributes["aria-live"] = "polite"

                            // The initial face, before any patch. counter() writes the three
                            // states the server reports; none of them is "nothing has happened".
                            p("counter__number") { +"0" }
                            p("counter__state") {
                                +"Press Start. The server will hold the connection open and write here."
                            }
                        }
                    }
                }
            }
        }
