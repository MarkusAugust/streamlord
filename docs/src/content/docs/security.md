---
title: "Security"
description: "Every byte from the browser is untrusted, and the protocol has more sharp edges than it looks."
---

> *Enterprise is a siege. I have watched the Greycloaks take a village with a single open gate;
> Streamlord leaves none.*

## No SSE injection

Selectors, event ids, retry intervals, header values and script attribute names are validated **at construction**.
A selector carrying a newline cannot forge a second `data:` line and smuggle in an event you did
not send. The guard is called `Wire`, it lives in `core.domain`, and it refuses rather than
sanitises. A selector with a line break is a bug in your code, not something to quietly fix.

## No script breakout

`ExecuteScript` neutralises `</script` inside the script body, and `</body>`, `</head>` and
`</html>`, which would make Datastar's client read the patch as a whole document, and a `<script`
after `<!--`, which would keep the element open past its end tag. It HTML-escapes attribute values.
`redirect()` quotes the URL as a JSON string literal and navigates from a `setTimeout`, the way
the official SDKs do, and refuses a URL with a scheme that does more than navigate, such as
`javascript:`, with `IllegalArgumentException`: only a relative URL or an `http`, `https`,
`mailto` or `tel` one is followed. `replaceUrl()` and `pushUrl()` quote it the same way.

## JavaScript-safe JSON

The built-in writer escapes U+2028, U+2029, `</` and `<!`, because the JSON it writes ends up in
JavaScript source as well as in JSON parsers. `data-signals` is compiled as an expression on the
client, and JSON you hand to `executeScript` lands in a `<script>` element.

`</` and `<!` are the two that matter in every browser. Inside a `<script>`, `</script>` ends the
element wherever it stands, in a string or not, and the rest of the JSON becomes markup. A
`<!--<script>` does the opposite: the parser then reads the next `</script>` as part of the
script, so the element stays open and takes in the markup after it as script text, up to the
first `-->` and the `</script>` after that, or to the end of the page if no `-->` comes. In Chrome,
`<script>window.a = {"s":"<!--<script>"}</script>` followed by a paragraph and a second script
leaves one script element, which never runs, and no paragraph; written as `\u003c!--<script>`,
both scripts run and the paragraph is there.

U+2028 and U+2029 are line terminators in JavaScript and ordinary characters in JSON. Since
ES2019 a string literal may hold them, so in a current browser a raw one does no harm: in
Chrome 155 with Datastar 1.0.4, `data-signals:r` set to `'{"s":"a<U+2028>b<U+2029>c"}'`, with the
raw characters, gives the same five-character string as the escaped form. An engine older than
Chrome 66, Firefox 62 or Safari 12, and Edge before 79, ends the string at the character and
throws `SyntaxError`, and so does a tool that reads JavaScript by the older rules. Written as
`\u2028`, the JSON means the same thing to every reader, at a cost of three bytes per character.

In `data-signals`, Streamlord also writes `\` as `\u005c`. Before compiling the value of
an attribute it reads, such as `data-signals` or `data-text`, Datastar's client cuts it into
statements at each `;` outside a string, and it reads a quote after a `\` as escaped. Written
as `\\`, `{"path": "C:\\", "sep": ";"}` would be cut inside the second value, and every
signal in the attribute would be lost. The literals the HTML helpers write (`js()`, `set()`,
`setAll()`, the fetch options, the Pro actions and regex filters) write `\`, quotes and `&` as
`\xHH`. In a value attribute they stay whole, and between the double quotes of an attribute
written in a string or a template, a quote in a value cannot end it.

## Signal names the client cannot hold

Every object in a browser already has `constructor`, `toString` and ten other properties from
`Object.prototype`. In Datastar 1.0.4's store, an object under `__proto__` makes the merge throw
and leaves the page deaf to every later signal. Two of them reach further:
`{"__proto__": {"__proto__": {"y": 1}}}` sets `y` on `String.prototype`, so every string on the
page has it, measured under Bun, in Chrome and over a real SSE stream. An object under one of
the others is written into the built-in property before every request that sends signals:
`{"constructor": {"prototype": {"x": 1}}}` sets `x` on `Object.prototype`, and
`{"toString": {"name": 1}}` makes every later request on the page fail. A map of names typed by
users, broadcast to every viewer, could otherwise do any of this to every page that shows it.

So a signal patch never carries such a key, at any depth, whether it came from pairs, a codec or
a JSON string. `patchSignals` checks every key with `SignalNames` and refuses `__proto__` with
any value. A name the DSL writes is checked as the client will read it, after the browser
lowercases the key and the client cuts it at `__` and applies any `__case`, and never has such
a part. Streamlord throws instead, before anything is sent;
[Patching signals back](/signals/#patching-signals-back) has the rule. The client itself refuses
nothing, so a server that sends `datastar-patch-signals` without Streamlord has to refuse these
keys the same way.
What you write yourself as an expression, such as `dataSignals("{constructor: {...}}")`, is
JavaScript, and is not checked.

## Bounded input

Incoming signals are capped (1 MiB by default, configurable) and the cap applies *while
reading*:

- a declared `Content-Length` above the limit is rejected before a single byte is read;
- a chunked body is cut off one byte past the limit;
- nothing larger than the cap ever sits in memory.

The cap is the same for the `datastar` query parameter that `@get` and `@delete` send, but the
server's limit on the request line comes long before it. Measured with the signal `{"q": "aaa…"}`:

| Server | Refused from a request line of about | With |
|---|---|---|
| Ktor on Netty | 4 KiB | 400 |
| Spring WebFlux (Reactor Netty) | 4 KiB | 414 |
| Ktor on CIO, Ktor on Tomcat, Spring on Tomcat | 8 KiB | 400 |
| Ktor on Jetty, Spring on Jetty | 8 KiB | 431, then 414 |

The request never reaches your handler, and Datastar's client reports an `error` and does not
try again. Send signals that can grow, such as a long text or a list, with `@post`, which carries
them in the body.

Signals read into a type are refused when objects and arrays nest more than 64 deep, before any
codec sees them. kotlinx.serialization reads a `JsonElement` by recursion, and twenty kilobytes
of `[` overflowed its stack: a `StackOverflowError` is no exception, so it went past every
handler to a 500. The limit is `Streamlord.MAX_SIGNALS_DEPTH`, the same 64 the built-in parser
and writer keep, so Jackson's own limits, 500 in Jackson 3 and 1000 in Jackson 2, are never
reached.

The parser caps nesting depth at 64 and the length of a number at 1000 characters, rejects
everything RFC 8259 rejects, and is run on every build against twenty thousand random inputs and
twenty thousand mutated documents, from fixed seeds, so the same ones each time. The second cap is there because a
million digits fit inside one mebibyte, and turning them into a number took over twenty seconds.

**You have two things to do here:** map `SignalsTooLargeException` to `413`, and map a body that
is not the signals you expected to `400`. The second is `JsonParseException` from the built-in
reader and `SignalsDecodeException` from a codec, and unmapped they are a `500`: a stranger
posting `query=gate` where JSON was expected gets a stack trace's worth of blame put on your
server. Any other `SignalsCodecException`, a value JSON cannot hold on the way out, is your own
bug, and a `500` is right for it. A third, `SignalsMediaTypeException`, is a body not declared
`application/json`, which [Forms from other sites](#forms-from-other-sites) explains; it is a
`SignalsDecodeException`, so it is a `400` if you map nothing more, and a `415` if you do.
Streamlord will not register a handler behind your back. On Spring it ships one you import
yourself, `StreamlordExceptionHandler`; on Ktor it is four handlers in `StatusPages`.

Without `StatusPages`, Ktor 3.6 answers an unhandled exception with a `500` whose body is the
exception's message, as `text/plain`, on CIO, Netty, Tomcat and Jetty alike. Streamlord's
messages then reach whoever sent the request: `Unexpected end of input (at position 5)` for
`?datastar={"a":`, `Incoming signals are 1100008 in size; the limit is 1048576` for a body that
is too large, and the type a codec failed to decode into. Map the four exceptions below, and
put a catch-all `exception<Throwable>` in `StatusPages` that answers `500` without the message,
so that nothing your own code throws is shown either.

```kotlin sample=ktor-application
install(StatusPages) {
    exception<SignalsTooLargeException> { call, _ ->
        call.respond(io.ktor.http.HttpStatusCode.PayloadTooLarge)
    }
    exception<JsonParseException> { call, _ ->
        call.respond(io.ktor.http.HttpStatusCode.BadRequest)
    }
    exception<SignalsDecodeException> { call, _ ->
        call.respond(io.ktor.http.HttpStatusCode.BadRequest)
    }
    exception<SignalsMediaTypeException> { call, _ ->
        call.respond(io.ktor.http.HttpStatusCode.UnsupportedMediaType)
    }
    // Everything else: a 500 that does not show the message.
    exception<Throwable> { call, _ ->
        call.respond(io.ktor.http.HttpStatusCode.InternalServerError)
    }
}
```

On Spring, WebMVC and WebFlux annotated controllers alike, import the advice where you configure
the application:

```kotlin sample=spring-controller
@org.springframework.context.annotation.Configuration
@org.springframework.context.annotation.Import(StreamlordExceptionHandler::class)
class WebConfig
```

It answers with the status and no body, so nothing of a codec's message reaches the client, and
it has the lowest precedence: an advice of your own that handles the same exceptions, or
`Exception`, decides instead when its `@Order` comes first; one with no `@Order` of its own ties
with it, and Spring's registration order picks. A status needs a response that has not started, so
read the signals before the stream opens: once a stream has sent its first event, or its first
heartbeat comment, at most five seconds after it opened while a heartbeat is on, the handler throws
the exception on and the container cuts the stream off, which Datastar's client retries. An advice
of your own should do the same when `response.isCommitted`: an answer there ends the stream as if
it had finished, and the client does not come back.

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
no header at all, for when a proxy or a header writer of your own already writes the policy. That
writer has to take the nonce from the request, `call.cspNonce` on Ktor and the
`CspNonceFilter.ATTRIBUTE` request attribute on Spring, so the two halves agree; Spring Security's
own CSP writer takes one fixed string and cannot.

### Three things the client does that will catch you out

All three are in Datastar 1.0.4's `engine/csp.ts`, and none of them announce themselves:

- **The nonce is read once, from `<html>`, when the client loads**, and the attribute is removed
  immediately after. A nonce patched into the page later does nothing, because CSP mode was
  already decided.
- **A missing `data-nonce` fails quietly.** The client stays on `Function(...)` and your
  `script-src` is what blocks the expressions. A page that loads is not evidence that the nonce
  arrived, which is why the test below is worth writing.
- **An empty `data-nonce` throws `NonceRequired`** and the client does not start. The plugin, the
  filter and `dataNonce` all refuse an empty nonce before it reaches the markup, so this one can
  only happen if you write the attribute yourself.

Patched-in scripts are the exception that needs no work from you: Datastar re-creates every
`<script>` it patches in and gives it the page's nonce. Never put a stream response's nonce on an
injected script, because the policy that governs it is the one that came with the document.

### CSP mode needs a server that answers the document

A nonce is per response, so the document itself has to be generated per request. A site served
from static files or a CDN cannot have one: every reader would get the same value out of the same
file, which is a constant, and a constant is not a nonce. This documentation site is in exactly
that position, which is why it runs Datastar without CSP while the page below, served by a Ktor
route, runs under a real policy.

If your pages are static, the honest answer is that Datastar's CSP mode is not available to you,
since the client has no hash-based alternative to `data-nonce`. What is still worth doing is the
rest of a policy, `object-src 'none'` and `base-uri 'self'` and a tight `default-src`, set as
headers by your host, with the expression compiler left out of it.

### A page that really runs under one

[streamlord-live/csp](https://streamlord-live-production.up.railway.app/csp) is served by the
service behind this site, under a policy with no `unsafe-eval` and no `unsafe-inline`, with the
nonce generated per response by the plugin above. The button's label, its click handler and the
counter are all Datastar expressions, so if it counts, every one of them was compiled through a
nonce-bearing script element. The policy it is served under is printed on the page.

### The test worth writing

Request the page from the test host, the way [Testing](/testing/) shows, and assert three things
about the one response: that the body carries a `data-nonce` at all, that the
`Content-Security-Policy` header contains `'nonce-'` followed by that same value, and that a
second request gets a different value. A nonce that repeats is a constant, and a constant is not
a nonce.

## Ordered delivery

Always on, and nothing you configure: every stream Streamlord hands you is locked this way.
One mutex per stream. Concurrent coroutines writing to the same stream never interleave their
frames, so a half-written `data: elements` line cannot appear in the middle of another event.

It matters the moment one endpoint feeds two parts of the page at their own pace. Here a clock
ticks every second while raids are appended to the `<ul id="raids">` already on the page:

```kotlin sample=ktor-routing
get("/watch") {
    call.respondDatastar {
        coroutineScope {
            launch {
                repeat(60) { tick ->
                    patchElements("""<span id="clock">$tick</span>""")
                    delay(1.seconds)
                }
            }
            launch {
                repeat(60) { n ->
                    patchElements(
                        """<li id="raid-$n">The Greycloaks took a village.</li>""",
                        selector = "#raids",
                        mode = ElementPatchMode.APPEND,
                    )
                    delay(2.5.seconds)
                }
            }
        }
    }
}
```

Each coroutine keeps its own pace, and when both are due at the same moment, which goes first is
up to the scheduler. Either way, what reaches the browser is one whole event after another. Two
and a half seconds in, the second raid, `raid-1`, lands between ticks 2 and 3:

```text
event: datastar-patch-elements
data: elements <span id="clock">2</span>

event: datastar-patch-elements
data: selector #raids
data: mode append
data: elements <li id="raid-1">The Greycloaks took a village.</li>

event: datastar-patch-elements
data: elements <span id="clock">3</span>
```

## Explicit API

This is about how Streamlord itself is built, so there is nothing for you to turn on; what you
notice is that autocomplete only offers what was meant to be public. Every published module is
compiled with `explicitApi()` and warnings as errors. Nothing leaves a module by accident, which
means the surface you can depend on is the surface that was meant.

## Raw user HTML

HTML a user wrote has to go through an allowlist sanitizer before it is patched in, such as the
OWASP Java HTML Sanitizer, keeping the tags and attributes you mean to allow and nothing else.
`data-ignore` does not contain it on its own: the user's `</div>` closes your wrapper, and what
follows it is live markup again, and Datastar's client runs every `<script>` it patches in,
`data-ignore` or not.

Wrap the sanitized HTML in `data-ignore` as well. Datastar then skips that element and everything
under it, so a `data-*` attribute that got past the sanitizer is markup and never an expression.

```kotlin sample=html
val fromTheUser = "<p>whatever they typed, sanitized</p>"

div {
    dataIgnore()
    unsafe { +fromTheUser }
}
```

Escaping is the other half, and it belongs wherever the interpolation happens. Hand Streamlord the
holes and that is Streamlord, which is what [`interpolate`](/strings/) does. Hand it a finished
string and the provenance is already gone, so the escaping was your template engine's job and
stays there.

## Forms from other sites

A page on any other site can hold this form, and a visitor who is signed in to yours sends it
with their cookies:

```html
<form method="post" action="https://your.site/transfer" enctype="text/plain">
  <input name='{"amount":1000,"to":"gorvek","pad":"' value='"}'>
</form>
```

The body is `{"amount":1000,"to":"gorvek","pad":"="}`, which is JSON. A form may send only
`text/plain`, `application/x-www-form-urlencoded` and `multipart/form-data`, and the browser sends
those without asking your server first. To send `application/json` to another site it has to ask
first, with a CORS preflight, and your server does not say yes.

Datastar's client declares every body that carries signals `Content-Type: application/json`, so
Streamlord refuses a body declared any other way, or not at all, with `SignalsMediaTypeException`
before any codec reads it. `StreamlordExceptionHandler` answers it with `415`, as does the
`StatusPages` block above. A declared type is checked before the body is read. Signals in the
query string, which `@get` and `@delete` send, are not affected, nor is a request with neither a
body nor a `Content-Type`. A request your own page sends with `contentType: 'form'` carries a
form rather than signals, and reading signals from it is refused the same way.

A client of your own that cannot set the header can be let through, at the cost of the check:

```kotlin sample=statements
val streamlord = Streamlord(requireJsonContentType = false)
```

On Ktor, `install(StreamlordPlugin) { requireJsonContentType = false }` does the same.

This closes the cheapest way in, not every way. A handler that changes something on `GET`, or a
CORS policy that answers yes to other sites, opens it again, so keep CSRF tokens or `SameSite`
cookies as well.

## What is still yours

Authentication, authorisation, CSRF tokens, rate limiting and audit logging. Streamlord takes no
view, and nothing enters your chain that you did not install: `CspNoncePlugin` above is opt-in
like everything else. It touches CSRF in two places: it refuses signals a form on another site
can send, as [Forms from other sites](#forms-from-other-sites) describes, and your CSRF token is
just another value in the markup, which the DSL will put wherever you say.
