# Streamlord

> *Hear me, mortal. You have come to the Forbidden Lands seeking power over the browser
> without writing a single rune of JavaScript. Sit. Read. And when you rise, the server
> streams will bend to your will.*
>
> — Gorvek, Lord of Streams

**Streamlord** is a Kotlin SDK for [Datastar](https://data-star.dev) 1.0.4. It speaks the
Datastar Server-Sent Events protocol exactly, reads the signals the browser sends back, and
serves two realms without favour: **Ktor** (the Sword) and **Spring** (the Shield). It is built
on ports and adapters, carries almost no dependencies, and treats every byte from the browser
as the untrusted thing it is.

```kotlin
get("/feed") {
    call.respondDatastar {
        patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
            li { +"A new head hangs on the wall" }
        }
        patchSignals("heads" to 13, "draft" to null)
    }
}
```

---

## The Modules

| Module | Role | Brings with it |
|---|---|---|
| `streamlord-core` | **The Soul.** The protocol, the events, the encoder, a strict JSON engine, the ports. | `kotlin-stdlib`, `kotlinx-coroutines-core` |
| `streamlord-html` | **The Tongue.** kotlinx.html DSL with every `data-*` attribute, action and modifier of Datastar 1.0.4. | `kotlinx-html` |
| `streamlord-ktor` | **The Sword.** `call.respondDatastar { }`, `call.readSignals<T>()`, a plugin. | nothing (Ktor is `compileOnly`) |
| `streamlord-spring` | **The Shield.** `response.datastarStream { }`, `request.readSignals<T>()`, WebFlux `Flow` mapping. Spring Boot 3 and 4. | nothing (Spring and the servlet API are `compileOnly`) |
| `streamlord-json-kotlinx` | Codec adapter for typed signals via kotlinx.serialization. | `kotlinx-serialization-json` |
| `streamlord-json-jackson` | Codec adapter via Jackson 3 (`tools.jackson`), for Spring Boot 4. | `jackson-databind` 3 |
| `streamlord-json-jackson2` | Codec adapter via Jackson 2 (`com.fasterxml`), for Spring Boot 3. | `jackson-databind` 2 |
| `streamlord-html-pro` | **Opt-in.** Helpers for the attribute names and actions of Datastar Pro. Contains no Pro code. | nothing beyond `streamlord-html` |

Streamlord's first law: **it brings nothing you do not already carry.** The core has no JSON
library; it has its own strict RFC 8259 parser and writer. Framework modules compile against
your framework and add no version of their own. You choose a codec adapter only when you want
data classes as signals.

```kotlin
dependencies {
    implementation("io.github.markusaugust.streamlord:streamlord-ktor:0.1.0")
    implementation("io.github.markusaugust.streamlord:streamlord-html:0.1.0")          // optional
    implementation("io.github.markusaugust.streamlord:streamlord-json-kotlinx:0.1.0")  // optional
}
```

---

## The Protocol, As It Truly Is

Datastar 1.0 knows only **two** SSE events. Older grimoires speak of `merge-fragments`,
`remove-fragments`, `merge-signals` and `remove-signals`; those spells died with 0.x.

| Event | What it does | In Streamlord |
|---|---|---|
| `datastar-patch-elements` | Patch complete HTML elements into the DOM | `PatchElements` |
| `datastar-patch-signals` | Merge-patch the signal store (RFC 7386) | `PatchSignals` |

Removal is a `PatchElements` with mode `remove`. A signal dies when it is patched to `null`.
`ExecuteScript` is sugar the SDK specification defines on top of `patch-elements`: a `<script>`
appended to `body`. Streamlord models it as its own event because you will want it.

Eight patch modes exist: `outer` (morph, default), `inner` (morph), `replace`, `prepend`,
`append`, `before`, `after`, `remove`. Every mode except `outer` and `replace` **requires a
selector**; Streamlord refuses to construct an event the client would reject.

The encoder is verified against the **official Datastar SDK golden files** (`sdk/tests/golden`)
on every build, and the Ktor adapter runs the official `/test` conformance server in-process.

---

## The Sword: Ktor

```kotlin
fun Application.module() {
    install(StreamlordPlugin) {
        codec = KotlinxSignalsCodec()   // optional; the built-in codec handles maps and JSON
    }

    routing {
        // One-shot stream: the response closes when the block returns.
        post("/search") {
            val signals = call.readSignals<SearchSignals>() ?: SearchSignals()
            val hits = repository.search(signals.query)
            call.respondDatastar {
                patchElements(selector = "#results", mode = ElementPatchMode.INNER) {
                    hits.forEach { li { +it.title } }
                }
                patchSignals("total" to hits.size)
            }
        }

        // Long-lived stream: lives until the flow ends or the client leaves.
        get("/counter") {
            call.respondDatastar(counter.map { n -> patchElements { span { id = "counter"; +"$n" } } })
        }

        // No stream at all: one HTML body, patched by the client.
        get("/panel") {
            call.respondElements(elements { div { id = "panel"; +"Quiet." } })
        }
    }
}
```

Signals are read by the protocol rule: query parameter `datastar` for `GET` and `DELETE`, the
body for `POST`, `PUT`, `PATCH` and `QUERY`. `call.readSignals()` returns a dependency-free
`Signals` object (`string("q")`, `int("page")`, `obj("user")`, `path("a", "b")`);
`call.readSignals<T>()` decodes through the codec.

---

## The Shield: Spring

### WebMVC

```kotlin
@RestController
class FeedController(private val streamlord: Streamlord) {

    @PostMapping("/search")
    fun search(request: HttpServletRequest, response: HttpServletResponse): StreamingResponseBody {
        val signals = request.readSignalsOr(SearchSignals(), streamlord)
        return response.datastarStream(streamlord) {
            patchElements(renderResults(signals.query), selector = "#results", mode = ElementPatchMode.INNER)
            patchSignals(signals.copy(page = signals.page + 1))
        }
    }

    @GetMapping("/panel")
    fun panel(): ResponseEntity<String> = datastarElements("""<div id="panel">Quiet.</div>""")
}

@Configuration
class StreamlordConfig {
    @Bean fun streamlord(mapper: ObjectMapper) = Streamlord(codec = JacksonSignalsCodec(mapper))
}
```

### WebFlux

```kotlin
@GetMapping("/counter", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun counter(): Flow<ServerSentEvent<String>> =
    counter.map { n -> PatchElements("""<span id="counter">$n</span>""") }.asServerSentEvents()
```

Spring ships no auto-configuration on purpose: one bean, declared by you, is the whole setup.

The Shield is compiled against Spring Framework 6.2 and tested against both 6.2 (Boot 3) and
7.0 (Boot 4) on every build. Pick the codec that matches your Boot generation:
`Jackson2SignalsCodec` from `streamlord-json-jackson2` for Boot 3, `JacksonSignalsCodec` from
`streamlord-json-jackson` for Boot 4.

---

## The Tongue: HTML

Every Datastar attribute, action and modifier, as kotlinx.html extension functions. No more
`"${'$'}count"` in your templates:

```kotlin
div {
    dataSignals("count" to 0, "open" to false)
    dataOnClick(post("/increment")) { debounce = 300.milliseconds; prevent = true }
    dataOnIntersect(get("/more")) { once = true; threshold = 50 }
    dataBind("search") { events = listOf("input", "blur") }
    dataText(signal("count"))
    dataShow(not("open"))
    dataClass("active", signal("open"))
    dataIndicator("busy")
    button { dataOnClick(statements(toggle("open"), set("count", 0))); +"Reset" }
}
```

Backend actions render their options only when you set them:

```kotlin
post("/form") { contentType = FetchOptions.ContentType.FORM; retry = FetchOptions.Retry.NEVER }
// @post("/form", {contentType: "form", retry: "never"})
```

The DSL also feeds events and streams directly: `stream.patchElements(selector = "#x") { ... }`,
`patchElements { ... }` for a `Flow`, and `elementsResponse { ... }` for a non-SSE reply.

---

## The Hexagon

```
                 driving port                       driven ports
   your code ──▶ DatastarStream ──▶ [ core ] ──▶ SseSink        ◀── Ktor channel / servlet stream
                                     │            SignalsCodec   ◀── kotlinx / Jackson / built-in
                 Streamlord.readSignals ──────▶   IncomingRequest ◀── ApplicationCall / HttpServletRequest
```

* **Domain** (`core.domain`): `DatastarEvent` (sealed), `ElementPatchMode`, `ElementNamespace`,
  `DatastarResponse`, and `Wire`, the guard that keeps line breaks out of single-line fields.
* **Protocol** (`core.protocol`): `SseEncoder` (pure), `SseFrame`, every constant of 1.0.4.
* **Ports** (`core.port`): the driving `DatastarStream`; the driven `SseSink`, `SignalsCodec`,
  `IncomingRequest`.
* **Application** (`core.application`): `Streamlord`, the configured facade the adapters call.
* **Adapters**: everything outside `streamlord-core`.

The core never imports a framework. A new realm (Vert.x, http4k, a plain `HttpServer`) is an
`SseSink` and an `IncomingRequest` away.

---

## Wards Against the Dark

Enterprise is a siege, and Streamlord is built for one.

* **No SSE injection.** Selectors, event ids, header values and script attribute names are
  validated at construction. A selector carrying `\n` cannot forge a `data: elements` line.
* **No script breakout.** `ExecuteScript` neutralises `</script` inside the script body and
  HTML-escapes attribute values. `redirect()` quotes the URL as a JSON string literal.
* **JavaScript-safe JSON.** The built-in writer escapes U+2028, U+2029 and `</`, because
  `data-signals` is evaluated as an expression on the client.
* **Bounded input.** Incoming signals are capped (1 MiB by default, configurable) *while being
  read*: a declared `Content-Length` above the limit is rejected before a byte is read, a chunked
  body is cut off one byte past it, and nothing larger ever sits in memory. The parser caps
  nesting depth (64), rejects everything RFC 8259 rejects, and is fuzzed on every build.
  Map `SignalsTooLargeException` to `413` in your framework's error handling.
* **CSP-ready.** `dataNonce(nonce)` on `<html>` enables Datastar's CSP mode; script events and
  responses accept a `nonce` attribute.
* **Ordered delivery.** One mutex per stream; concurrent coroutines never interleave frames.
* **Explicit API.** Every module is compiled with `explicitApi()` and warnings as errors.

---

## Datastar Pro

Datastar Pro is licensed software, and none of it lives in this repository: no plugin source,
no bundle, no inspector. The open-source modules cover the free Datastar bundle only.

If you hold a Pro license, add `streamlord-html-pro`. It is a separate artifact so that using
it is an explicit choice. It contains nothing but kotlinx.html helpers that write the publicly
documented Pro attribute names and action strings (`dataPersist()`, `dataScrollIntoView()`,
`dataQueryString()`, `clipboard()`, `fit()`, `intl()`, ...). They are inert until you load the
Pro bundle you licensed; Streamlord does not ship it, fetch it or unlock it.

```kotlin
div {
    dataPersist("draft", SignalFilter.include("^form"), session = true)
    dataQueryString(history = true)
    button { dataOnClick(clipboardExpr(signal("code"))); +"Copy" }
}
```

## Roadmap

* `0.2`: Spring Boot auto-configuration module (opt-in), Ktor `data-*` helpers for CSRF tokens.
* `0.3`: pluggable templating adapters (Pebble, Thymeleaf) behind an `ElementsRenderer` port.

## Building

```
./gradlew build
```

JDK 21 builds it; the artifacts target JDK 17.

## License

MIT. Take it, wield it, and may your streams never buffer.
