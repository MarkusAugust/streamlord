---
title: "Security"
description: "Every byte from the browser is untrusted, and the protocol has more sharp edges than it looks."
---

> *Enterprise is a siege. I have watched the Greycloaks take a village with a single open gate;
> Streamlord leaves none.*

## No SSE injection

Selectors, event ids, header values and script attribute names are validated **at construction**.
A selector carrying a newline cannot forge a second `data:` line and smuggle in an event you did
not send. The guard is called `Wire`, it lives in `core.domain`, and it refuses rather than
sanitises. A selector with a line break is a bug in your code, not something to quietly fix.

## No script breakout

`ExecuteScript` neutralises `</script` inside the script body and HTML-escapes attribute values.
`redirect()` quotes the URL as a JSON string literal and navigates from a `setTimeout`, the way
the official SDKs do.

## JavaScript-safe JSON

The built-in writer escapes U+2028, U+2029 and `</`. This is not decoration: `data-signals` is
evaluated as an expression on the client, and U+2028 is a line terminator in JavaScript source
but not in JSON. A string containing one, written naively, ends the expression.

## Bounded input

Incoming signals are capped (1 MiB by default, configurable) and the cap applies *while
reading*:

- a declared `Content-Length` above the limit is rejected before a single byte is read;
- a chunked body is cut off one byte past the limit;
- nothing larger than the cap ever sits in memory.

The parser caps nesting depth at 64, rejects everything RFC 8259 rejects, and is fuzzed on every
build.

**You have one thing to do here:** map `SignalsTooLargeException` to `413` in your framework's
error handling. Streamlord will not register a handler behind your back.

```kotlin sample=ktor-application
install(StatusPages) {
    exception<SignalsTooLargeException> { call, _ ->
        call.respond(io.ktor.http.HttpStatusCode.PayloadTooLarge)
    }
}
```

## CSP

Datastar compiles attribute expressions with `new Function`, which a strict `script-src` blocks.
A `data-nonce` on the `<html>` element switches the client to compiling through nonce-bearing
script elements instead, and the value in that attribute has to be the value in the response's
`Content-Security-Policy` header.

Install the plugin and read the nonce off the call. Both ends come from one variable, so they
cannot drift:

```kotlin sample=ktor-application
install(CspNoncePlugin)

routing {
    get("/dashboard") {
        val nonce = call.cspNonce

        call.respondText(contentType = io.ktor.http.ContentType.Text.Html) {
            kotlinx.html.stream.createHTML().html {
                dataNonce(nonce)
                body {
                    div { dataSignals("count" to 0) }
                }
            }
        }
    }
}
```

Spring WebMVC is the same thing as a filter bean:

```kotlin sample=spring
@Bean
fun cspNonceFilter(): CspNonceFilter = CspNonceFilter()

@GetMapping("/dashboard", produces = [org.springframework.http.MediaType.TEXT_HTML_VALUE])
fun dashboard(request: HttpServletRequest): String =
    kotlinx.html.stream.createHTML().html {
        dataNonce(request.cspNonce)
        body {
            div { dataSignals("count" to 0) }
        }
    }
```

On WebFlux the filter is yours, because a `WebFilter` returns a `Mono` and that is the one
dependency this adapter will not take. The Streamlord half is a single call:

```kotlin sample=none
class CspNonceWebFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        exchange.installCspNonce()
        return chain.filter(exchange)
    }
}
```

`exchange.cspNonce` then reads it back in the handler, exactly as `call.cspNonce` does.

### The policy is yours

The default policy is `CspNonce.starterPolicy`, which is deliberately strict and deliberately
small: same-origin everything, scripts from this origin or carrying the nonce, no plugins, a
`<base>` that cannot be moved. It is somewhere to start, not an answer, and the first thing it
does in a real application is break something you then have to name. Replace it as soon as you
know what your page loads:

```kotlin sample=ktor-application
install(CspNoncePlugin) {
    policy = { nonce -> "default-src 'self'; script-src 'self' 'nonce-$nonce'; img-src https://cdn.example.com" }
    reportOnly = true
}
```

`reportOnly = true` writes `Content-Security-Policy-Report-Only` instead, so the browser logs
what the policy would have blocked and blocks nothing. On an application that has never had a
policy, spend a deploy or two there first. Setting `policy = null` generates the nonce and writes
no header at all, which is what you want when Spring Security or a proxy already writes the
policy and you only need the two halves to agree.

### Three things the client does that will catch you out

All three are in Datastar 1.0.4's `engine/csp.ts`, and none of them announce themselves:

- **The nonce is read once, from `<html>`, when the client loads**, and the attribute is removed
  immediately after. A nonce patched into the page later does nothing, because CSP mode was
  already decided.
- **A missing `data-nonce` fails quietly.** The client stays on `Function(...)` and your
  `script-src` is what blocks the expressions. A page that loads is not evidence that the nonce
  arrived, which is why the test below is worth writing.
- **An empty `data-nonce` throws `NonceRequired`** and the client does not start. Streamlord
  refuses an empty nonce before it reaches the markup, so this one can only happen if you write
  the attribute yourself.

Patched-in scripts are the exception that needs no work from you: Datastar re-creates every
`<script>` it patches in and gives it the page's nonce. Never put a stream response's nonce on an
injected script, because the policy that governs it is the one that came with the document.

### The test worth writing

Request the page from the test host, the way [Testing](/testing/) shows, and assert three things
about the one response: that the body carries a `data-nonce` at all, that the
`Content-Security-Policy` header contains `'nonce-'` followed by that same value, and that a
second request gets a different value. A nonce that repeats is a constant, and a constant is not
a nonce.

## Ordered delivery

One mutex per stream. Concurrent coroutines writing to the same stream never interleave their
frames, so a half-written `data: elements` line cannot appear in the middle of another event.

## Explicit API

Every published module is compiled with `explicitApi()` and warnings as errors. Nothing leaves a
module by accident, which means the surface you can depend on is the surface that was meant.

## What is still yours

Authentication, authorisation, CSRF tokens, rate limiting and audit logging. Streamlord takes no
view, and nothing enters your chain that you did not install: `CspNoncePlugin` above is opt-in
like everything else. The one place it touches the subject is that your CSRF token is just
another value in the markup, and the DSL will put it wherever you say.
