# Streamlord

> *The elders of Bonereach asked Gorvek what a warrior wants of a stream.*
> *"That it flows when I say flow. That it stops when I say stop. That it carries my will to
> the far shore, and nothing else."*
> *"And the JavaScript?"*
> *"I do not know the word."*
>
> — Gorvek of Bonereach, who walked out of the Ashfall with iron in his hand

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

> *Every soul in Gallowmark knows my name, and not one knows my face. I took three rubies
> from the Iron Crown of Kell and the land fell apart in my hands. Now I will take one thing
> from you: the belief that the browser must be bought with JavaScript.*
>
> *Sit. Read. The stream was always yours to bend.*
>
> — Sarn the Faceless, whom the river-priests of Thurn call the Lord of Streams

What follows is Sarn's grimoire. The code is deadly serious; the voice is not ours.

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
    implementation("io.github.markusaugust.streamlord:streamlord-ktor:0.2.0")
    implementation("io.github.markusaugust.streamlord:streamlord-html:0.2.0")          // optional
    implementation("io.github.markusaugust.streamlord:streamlord-json-kotlinx:0.2.0")  // optional
}
```

---

## The Protocol, As It Truly Is

*You were told there were five rites. Whoever told you that read a grimoire from before the
Ashfall.* Datastar 1.0 knows only **two** SSE events. Older grimoires speak of
`merge-fragments`, `remove-fragments`, `merge-signals` and `remove-signals`; those spells
died with 0.x.

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

### The `$` trap

`$` opens a template in Kotlin strings, so `dataOnClick("$count++")` sends `++` to the browser,
and `dataText("$user.name")` sends `.name`. Datastar ignores both without a word. Streamlord does
not: every expression helper runs the text through `ExpressionGuard` (in `streamlord-core`),
which throws `InterpolatedExpressionException` at render time when the text has the shape only
an eaten signal leaves behind (empty, only operators, an operator with a missing side). Three
ways to write it right, in order of preference:

```kotlin
dataOnClick(increment("count"))       // the helpers: signal, set, toggle, not, increment, decrement, statements
dataOnClick($$"$count++")             // Kotlin 2.2+: in a $$"..." literal a single $ is just a dollar
dataOnClick("${'$'}count++")          // any Kotlin
```

The VS Code extension flags the same trap while you type. Existing 0.1.0 code is unaffected
unless it already shipped a broken expression, in which case you now get a stack trace instead
of a silent page. For HTML written as a string, the same guard is available as `ElementsGuard`;
see the next section.

Backend actions render their options only when you set them:

```kotlin
post("/form") { contentType = FetchOptions.ContentType.FORM; retry = FetchOptions.Retry.NEVER }
// @post("/form", {contentType: "form", retry: "never"})
```

Every attribute plugin, modifier and action of the 1.0.4 client is covered, audited against its
source. Using the aliased bundle? Set `DatastarAttributes.prefix = "data-star-"` once at startup.

The DSL also feeds events and streams directly: `stream.patchElements(selector = "#x") { ... }`,
`patchElements { ... }` for a `Flow`, and `elementsResponse { ... }` for a non-SSE reply.

---

## Three ways to write markup

The DSL is one way, not the way. Every place Streamlord takes HTML takes a `String`, so a
function that returns a multi-line string, or a template engine rendering to one, is as much a
first-class citizen as `div { }`. Pick what your team reads best; the SDK and the editor
tooling serve all three.

### Strings

```kotlin
@Language("HTML")
fun counter(count: Int): String = $$"""
    <div id="counter" data-signals="{count: $$count}">
        <button data-on:click="$count++">Raise</button>
        <span data-text="$count"></span>
    </div>
""".trimIndent()

call.respondDatastar { patchElements(counter(state.count)) }
```

Two things make this comfortable:

* **`$$"""..."""`** (Kotlin 2.2+). In a multi-dollar string a single `$` is just a dollar, so
  `$count` reaches the browser as the signal it is, and `$$count` is the Kotlin template.
  Without it, `$count` either fails to compile or, when a `count` happens to be in scope,
  silently ships `data-text=""`. On older Kotlin, write `${'$'}count`.
* **`@Language("HTML")`** from `org.intellij.lang.annotations`, which your build already has
  through the Kotlin standard library. IntelliJ then treats the string as HTML: highlighting,
  tag completion, and, with the official [Datastar plugin](https://plugins.jetbrains.com/plugin/26072),
  completion of every `data-*` attribute. Streamlord's own parameters (`patchElements`,
  `PatchElements`, `respondElements`, `datastarElements`, `ElementsResponse`; `JSON` for
  signals, `JavaScript` for scripts) carry the annotation, so a literal passed straight in is
  injected without you writing anything. On your own functions and properties, add it yourself.

The VS Code extension treats a string as HTML when it opens with a tag or carries the
annotation or a `// language=HTML` comment, and gives it the same diagnostics, completions,
hover and highlighting as a template file.

### Templates

Pebble, Thymeleaf, JTE, kte, FreeMarker, Velocity, Mustache: render, then pass the string.

```kotlin
patchElements(pebble.getTemplate("cards/sak.peb").render(mapOf("sak" to sak)), selector = "#saker", mode = APPEND)
```

There is no `$` trap here, because the template engine owns the file. IntelliJ with the
Datastar plugin completes attributes in Thymeleaf, FreeMarker and JTE files; the VS Code
extension does so for all of the above and understands each engine's syntax well enough not
to flag it.

### The guard for strings and templates

The DSL guards its own expressions. For HTML that arrives as a string, opt in:

```kotlin
install(StreamlordPlugin) { guardElements = true }     // Ktor
Streamlord(guardElements = true)                       // the instance behind a Spring bean, or anywhere
```

Every element patch and elements response that leaves through that instance is walked by
`ElementsGuard`, which finds each `data-*` attribute whose value is an expression and runs it
through `ExpressionGuard`. A `data-text=""` that a Kotlin template left behind throws
`InterpolatedExpressionException` naming the attribute, instead of reaching the browser. The same
walk catches a mistyped attribute: `data-onn:click` is one letter from `data-on:click`, and
throws `MistypedAttributeException` saying so. A bare name without key or modifier is only
judged against the long Datastar names (`data-signal`, `data-indicater`), because short ones
have too many honest neighbours: `data-test`, `data-kind`, `data-size` and `data-theme` are
yours and pass. It is a small, allocation-free scan that skips `<script>` and `<style>` bodies,
off by default so that the choice is yours; `ElementsGuard.check(html)` is also there to call
directly, for example in the tests of your markup functions. In IntelliJ, where nothing flags
an unknown `data-*` name, this is the check. Spring has no auto-configuration, so its helpers
take the bean: `datastarElements(html, streamlord = bean)`, `response.toResponseEntity(streamlord = bean)`,
`events.asServerSentEvents(bean)`; with `Streamlord.Default` the guard is off.

### Casing: keys in kebab-case, signals in camelCase

The browser lowercases every attribute name, so `data-signals:fooBar` reaches Datastar as
`foobar`. Datastar then reads the keys of the signal attributes (`signals`, `computed`, `bind`,
`ref`, `indicator`, `match-media`) in camelCase: `foo-bar` is the signal `$fooBar`. The keys of
`on` and `class` stay as written unless `__case` says otherwise (`widget-loaded__case.camel`
listens to `widgetLoaded`), and `attr`, `style` and `animate` use the key as it comes. One rule
covers it: **write keys in kebab-case, read signals in camelCase, and reach for `__case` only
when the name really has capitals.**

The DSL applies the rule so you never trip over it. A camelCase name is written as the kebab
key that comes back as that name, with `__case` added where Datastar's default is not camel:

```kotlin
dataSignals("fooBar", "1")            // data-signals:foo-bar            -> $fooBar
dataOn("widgetLoaded", "x()")         // data-on:widget-loaded__case.camel
dataClass("isOpen", signal("open"))   // data-class:is-open__case.camel
dataAttr("ariaLabel", "'x'")          // data-attr:aria-label
signal("foo-bar")                     // $fooBar, because that is what Datastar calls it
```

An explicit `case =` is kept, but the key is still written in kebab-case, because the browser
lowercases it either way: `dataSignals("fooBar", "1", case = Case.KEBAB)` is
`data-signals:foo-bar__case.kebab`. Keys that are not signal names keep their own characters
(`dataClass("hover:bg-red-500", ...)`, `dataAttr("xlink:href", ...)`, `dataStyle("--brand", ...)`),
and references may index (`signal("items[0].name")`). A blank name, or one with whitespace or
quotes, throws `InvalidSignalNameException` at render time. Handled is not hidden: the VS Code
extension puts a hint on every such call saying what goes on the wire, so the rule is learned
where it applies. The default per attribute lives in the catalog as `keyCase`, which the SDK
test and the extension both follow. For strings and templates, the VS Code
extension flags a capital letter in a key (with the kebab-case fix) and a `$foo-bar` in an
expression (which reads as `$foo` minus `bar`) with the camelCase fix.

### What each gets

| | DSL | Strings | Templates |
|---|---|---|---|
| Passed to the SDK as | `div { }` blocks | `String` | `String` |
| `$` trap | caught by the helpers | `$$"""` avoids it; `guardElements` catches it | none |
| IntelliJ | Kotlin plugin: completion, KDoc, types | `@Language("HTML")` + Datastar plugin: attribute completion | Datastar plugin: attribute completion |
| VS Code | expression diagnostics, completion, hover | markup and attribute diagnostics, completion, hover, highlighting | the same, per language |

---

## The Eye: VS Code

`editors/vscode` holds the Streamlord extension, on the Marketplace as `MarkusAugust.streamlord`:
diagnostics with quick fixes for Datastar expressions and markup inside Kotlin strings, whether
handed to the DSL or free-standing, and in HTML and template files (Kotlin `$` interpolation
traps, syntax errors with the right column, missing ids, unknown attributes and modifiers),
completions for signals, actions, attributes, modifiers, ids and classes, snippets, hover docs,
syntax highlighting, and a Stream Inspector that shows a live SSE stream decoded with saved
requests and route code lenses. The template languages of the JVM (JTE, kte, FreeMarker,
Velocity, Mustache, Pebble; Thymeleaf is plain HTML) are on by default. Everything it knows
comes from `catalog/datastar-1.0.4.json`, which the SDK's own tests bind to the DSL.

IntelliJ IDEA gives you the DSL itself for free through the Kotlin plugin: completion, KDoc
and type errors for every call. Inside strings, `@Language("HTML")` on Streamlord's parameters
turns on HTML injection, and the official Datastar plugin adds attribute completion there and
in template files (it needs the JavaScript plugin, so IntelliJ IDEA Ultimate). A Streamlord
plugin for IntelliJ, with the expression checks and actions, is still planned.

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

*Enterprise is a siege. I have watched the Greycloaks take a village with a single open
gate; Streamlord leaves none.*

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

## Building and releasing

```
./gradlew build
```

JDK 21 builds it; the artifacts target JDK 17.

Releases go to Maven Central from CI only: bump `version` in `gradle.properties`, commit, push
a tag `v<version>`. The `publish-maven-central` job checks that the tag matches, then signs and
publishes every module under `io.github.markusaugust.streamlord` through the Central Portal
with automatic release. The VS Code extension has its own tag, `vscode-v<version>`.

## License

MIT. Take it, wield it, and may your streams never buffer.

*Gorvek, Sarn, Gallowmark and every other name in this grimoire are our own
invention. Any resemblance to legends told at other tables is the mead's doing.*
