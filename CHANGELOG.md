# Changelog

All notable changes to Streamlord are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `sendLatest(states, minInterval, resumeFrom) { render }` on a stream: renders the newest state
  and sends it, at most once per interval, and not at all when nothing changed. Each render's
  fingerprint is its event id, so a reconnect that passes `last-event-id` back as `resumeFrom` is
  not sent what it already shows. `SseEncoder.fingerprint` computes it. Documentation: the Live
  views page covers commands and the view, the element that opens the stream, and
  `requestCancellation`.
- `replaceUrl(url)` and `pushUrl(url)` on a stream: the server puts the URL of the state it
  rendered in the address bar, replacing the current history entry or adding one, without a
  reload. The URL is quoted as `redirect` quotes it; pass a path on the page's own origin.
  Documentation: a Live views page, which starts with them.
- Documentation: a Components and plain JavaScript page (attributes down and events up for
  custom elements, window events from plain scripts, `data-ignore-morph` on both sides of a
  morph, optimistic state rolled back on a failed fetch), and the signals page covers signals that
  stay in the browser, `filterSignals` and `payloadExpr`.
- `Streamlord(heartbeat = ...)` and `heartbeat` on the Ktor plugin: a `: keep-alive` comment on
  every Ktor and Spring WebMVC stream that has written nothing for that long, so an idle proxy
  keeps the connection open. With a `StreamAuthorisation`, an idle stream is asked again on the
  heartbeat's schedule. WebFlux's `asServerSentEvents` is not covered. Off by default.
- `streamlord-analysis`, for both editors: `signalReferencesInKotlin` and `signalReferencesInHtml`, every `$name` a
  Datastar expression reads, with source offsets; `collectSignalDefinitions`, the signals a file
  defines without the ones it only reads, plus every `$name = ...` an expression assigns; and
  `unknownSignalIssue`, the `unknown-signal` warning for a read no definition covers, with the
  nearest defined name as the fix. `isKnownSignal` counts `$user.name` as known when `user` is
  defined, and `$form` when `form.email` is.
- `streamlord-analysis`: `ServerLog`, which reads where a server says it started from its log
  (Spring Boot's Tomcat, Jetty, Undertow and Netty lines in 3.5 and 4.0, Ktor's "Responding at"),
  context path included, and which lines are warnings or errors; `RunningServer`.
  `mergeVariables` takes a running server as `baseUrl` when the env file sets none, as the new
  `VariableSource.RUNNING` with the name it was started under; `baseUrlSuggestion` and
  `withBaseUrl` offer and write its URL when the file's differs.

### Changed

- `streamlord-analysis`, for both editors: signal collection, for completion, `signalDrift` and
  the new check alike, honours `__case` on a keyed attribute (`data-signals:my-value__case.snake`
  is `my_value`, `__case.pascal` gives `MyValue`) and reads the keys of JSON text handed to
  `patchSignals`, as in `patchSignals("""{"count": 1}""")`. `keyName(key, case)` names the
  signal a key declares.

- `streamlord-analysis`, for both editors: `Route` carries the `query` parameters and `headers`
  its handler reads, as `RouteParam`s that say whether they are required: Spring's
  `@RequestParam` and `@RequestHeader` from the parameter list, and Ktor's
  `queryParameters["x"]` from the handler's block. `Requests.routeUrl`, `routeHeaders` and
  `routeOptional` turn a route into what "Open in Stream Inspector" writes, a `{{name}}` for each
  parameter. `pathParams` and `fillPath` are gone with the dialog they served.
- `streamlord-analysis`: `.streamlord/env.json` takes a fourth key, `params`, an object of texts.
  Each name in it is a variable for the URL, where its value is encoded, and the headers.
  `resolveRequest` reports a param that is missing or empty, and `withEnvVariables` adds it to
  `params` without touching the rest of the file. `Env` has a `params` field.
- `streamlord-analysis`: an env file that is not valid JSON is reported with where, such as
  `at line 3, column 1` for a comma after the last value.

### Fixed

- `streamlord-analysis`, for both editors: a route whose mapping had no leading slash, such as
  `@RequestMapping("api/hent")` or Ktor's `route("api")`, was found as `api/hent/visning`, and
  the Stream Inspector opened it as `http://localhost:8080api/hent/visning`. Every route path now
  starts with `/`.
- `streamlord-analysis`: `Requests.sendableUrl` refuses a host or a port that `java.net.URI`
  cannot read, such as `localhost:8080api`, `localhost:8080:` or `my_service`, and says which.
  The HTTP client used to refuse them later as "unsupported URI", naming only the URL. A test
  holds the rule to the JDK's own verdict.
- `streamlord-analysis`: `Requests.emptyBodyHint`, the note the Stream Inspector shows under an
  error response that came without a body: the reason is then in the server's log.

## [0.11.1] - 2026-10-05

### Added

- Documentation: the site serves `/llms.txt`, an index of every page with what it is about, and
  `/llms-full.txt`, every page in one file, after llmstxt.org. Both are built from the pages and
  the navigation, and the link check covers their addresses.
- Documentation: the introduction and the README open with the same endpoint written by hand
  against the protocol and with Streamlord, and a screenshot of the editor refusing
  `dataText("$count")`. The introduction shows the three ways to write markup as tabs, and the
  README names them. The testing page says that Ktor 3.6.0's test host hangs on a streamed
  response of 1 MiB or more (KTOR-9968).
- Documentation: the testing page says that on Ktor 3.6.0 an exception thrown inside
  `respondDatastar` does not fail a test, and which assertions still catch it.

## [0.11.0] - 2026-10-04

### Added

- `streamlord-analysis`: `Requests.parseEnv`, which reads `.streamlord/env.json` with its three
  keys and reports anything else, and `mergeVariables`, `describeVariables`, `withEnvVariables`,
  `unreachableHint`, `variableCompletions` and `newRequestLabel`, which the Stream Inspector in
  both editors uses. `resolveRequest` now takes those variables and returns errors and the keys
  that are not set; `parseEnvFile` is gone.

### Fixed

- `streamlord-analysis`, for both editors: a Spring method took the `@RequestMapping` prefix of
  the class before it in the file. A class without `@RequestMapping` now has no prefix, a nested
  class keeps its mapping to itself, and a class mapping is no longer reported as a route when
  another annotation with parentheses in a string, such as `@PreAuthorize("hasRole('ADMIN')")`,
  stands before the class.
- `streamlord-analysis`, for both editors: `patchElements("#rows") { li { } }`, the kotlinx.html
  form, had its selector read as markup and flagged as text outside an element, and a mode
  without a selector went unreported. With a trailing lambda the first string is the selector
  and the second argument the mode.
- `streamlord-analysis`: `Requests.sendableUrl` gives the URL the Stream Inspector sends for the
  URL of a curl line, or the reason it cannot be sent as written.

## [0.10.1] - 2026-10-04

### Fixed

- `streamlord-spring` on Spring Boot: every `readSignals` and `datastarStream` threw
  `NoSuchMethodError: BuildersKt.runBlockingK$default`. Boot pins kotlinx-coroutines in its BOM
  (1.8.1 under Boot 3.5, 1.10.2 under Boot 4.0 and 4.1) and downgrades anything newer, and 0.10.0
  was compiled against 1.11.0. Every module is now compiled against 1.8.1, the oldest pin, and the
  Spring adapter's tests run on 1.8.1, on 1.10.2 with Framework 7, and on the newest release. On
  0.10.0 the way out is `ext["kotlin-coroutines.version"] = "1.11.0"` in your build.
- `streamlord-core`: a `StreamAuthorisation` refusal now holds when the handler does not stop.
  A handler that caught the exception inside a loop went on writing to a reader who had just
  been refused, with no further check. Every write after a refusal is now refused again, only
  `onRefused` may still write, and `Streamlord.stream` reports such a stream as refused.
- Documentation: the Spring WebMVC and WebFlux samples took `Streamlord` as a parameter of the
  handler method. Spring constructs a new default instance for such a parameter instead of
  injecting the bean, so the configured codec and `guardElements` were silently ignored. The
  samples now take the bean through the controller's constructor, and the build refuses a
  sample that does otherwise.
- `streamlord-core`: `interpolate` let a value into attributes the browser acts on. It now
  refuses a value in an event handler (`on*`) or `srcdoc`, takes only a number in `style`, and at
  the start of a URL attribute (`href`, `src`, `action` and the like) takes a relative URL or an
  `http`, `https`, `mailto` or `tel` one. `Position` gains `URL` and `STYLE`.
- `streamlord-html`: `dataNonce` refuses an empty or malformed nonce, as the plugin and the
  filter already did.
- `streamlord-core`: `ElementsGuard` threw `IllegalArgumentException` on a numeric character
  reference that names no character, such as `&#99999999;`. It is left as written.
- `streamlord-json-jackson2`: `Jackson2SignalsCodec.default()` refused a body carrying a signal
  the type does not name, which is every body from a page with more signals than one handler
  reads. It now ignores unknown properties, as Spring's mapper and the other codecs do.
- `streamlord-json-kotlinx`: a body of the wrong shape was reported with a hint to name a
  serializer for a native image. The hint now comes only with a failed serializer lookup.
- `streamlord-spring`: a WebFlux event wrote `retry: 1000`, the protocol default the encoder
  leaves off.
- Documentation, each checked by running it: the `$` trap ships the variable's value, not an
  empty string, and the runtime guard catches only the empty case; `signal()` and the other
  expression helpers refuse blank names and whitespace, not quotes; under Spring Boot's
  dependency-management plugin Boot's Jackson version wins in Gradle too; a body that will not
  parse is a `500` unless you map it; ten modules are published, not nine; and the bytes on the
  wire are identical on Ktor and WebMVC, while WebFlux writes the same frames in Spring's
  spelling.

- Every published jar now carries `META-INF/LICENSE`. The licence page said the licence text
  travels in the jar, and it did not.
- Documentation, from a register of 414 claims checked one by one: Spring Boot's default MVC
  executor runs eight streams at once and queues the rest, where the page said it created a
  thread per stream without limit; the golden files are compared as an equivalence, not byte for
  byte; `sharedLibrary.set(false)` is the default without `java-library`; stale coordinates and
  transcripts are rewritten rather than failing the build; completion collects signals from the
  whole project; and four smaller corrections on the roadmap and changelog pages.

- `streamlord-core`: a number of a million digits in a signal fits inside the default size limit
  and cost over twenty seconds of CPU the first time `int`, `long` or `decimal` read it. The
  parser now refuses a number longer than `JsonParser.MAX_NUMBER_LENGTH`, 1000 characters.
- `streamlord-core`: `JsonWriter` wrote a `BigDecimal` with an enormous exponent in plain
  notation, two billion zeros for `1E+2000000000`. It now keeps scientific notation past a scale
  of 100. A `Float` is written in its own shortest form, a map whose keys are written alike is
  refused, and the writer stops at the depth the parser stops at. The parser no longer takes the
  digits of other scripts in a `\u` escape. `JsonObject` and `JsonArray` equality against a plain
  `Map` or `List` now holds in both directions.
- `streamlord-core`: `SseDecoder` read streams the browser would not act on. A last message with
  no blank line after it is no longer an event, a line may end in a bare CR, a leading byte order
  mark is dropped, mode and namespace are matched as written, a signal patch with no `signals` is
  malformed, an event that is not Datastar's is skipped whatever its fields hold, and an empty
  `id` is no id.
- `streamlord-test`: `assertSignal` honours `onlyIfMissing` leaf by leaf, as the client's merge
  does; compares numbers by value, so `13` is `13.0`; takes `null` to mean the signal is not
  there; finds a key that holds a dot; and fails with an `AssertionError` on signals that are not
  JSON. `assertExactly` compares the frames two events put on the wire, so it takes the
  `ExecuteScript` that was sent and ignores a `retry` of the protocol default.
- `streamlord-html`: `dataBind { prop = "valueAsNumber" }` wrote the property in camelCase into
  the attribute name, which the browser lowercases; it is written `value-as-number`, which the
  client turns back. A name holding `__` is refused, because Datastar reads modifiers from there.
  A positive duration shorter than a millisecond is refused instead of written as zero.
  `SignalFilter.regexLiteral` writes an empty pattern as `/(?:)/`, escapes a line break, and
  refuses inline flags. `JsLiteral` quotes `__proto__`.
- `streamlord-core`: `ExpressionGuard` no longer refuses an expression holding a comment, a regex
  after `=>`, or a number ending in a dot.

- `streamlord-analysis`, and with it both editors, from a review that reproduced each of these:
  ordinary attributes such as `data-id`, `data-test` and `data-href` were flagged as misspelt
  Datastar attributes, with a fix that rewrote them; HTML with optional end tags (`<li>a<li>b`)
  was an error; `__debounce.500`, `$foo.0.name`, HTML entities in an attribute value and an `@`
  inside a string were all refused though Datastar accepts them; a string followed by
  `.trimIndent()` was not checked at a call site, so the CI check for the `$` trap missed it; a
  positional selector in `patchElements` was not seen; a quick fix on an escaped dollar wrote a
  live Kotlin template; U+0130 in the text shifted every later offset; a CRLF split across two
  chunks broke an SSE message in two; and `map.get("key")` was reported as a route. New errors
  say what the Datastar client refuses at run time: `action-space`, `missing-value`,
  `key-and-value` and `missing-key-or-value`.
- `streamlord-analysis`: the JavaScript parser is now a port of acorn, the parser the VS Code
  extension uses. Over a corpus of 682 expressions the two disagreed on the verdict for 67 and
  now disagree on none.
- VS Code extension: a quick fix for an expression inside an HTML attribute edited the start of
  the tag instead of the expression.
- `signalDrift` collects JSON-form declarations, honours `@SerialName`, and counts an accessor
  as a signal read only on a value that came from `readSignals`.

### Removed

- `streamlord-html`: the deprecated aliases `html.ExpressionGuard` and
  `html.InterpolatedExpressionException`, left from 0.1.1. With both `core.domain.*` and `html.*`
  imported they made either name ambiguous. Import them from `core.domain`.

### Added

- `streamlord-spring`: `Flow<DatastarEvent>.asDatastarResponse(streamlord)`, the WebFlux stream
  as a `ResponseEntity` carrying `Cache-Control: no-cache` and `X-Accel-Buffering: no`. A
  controller returning the bare flow from `asServerSentEvents` sent neither, so nginx held the
  events back until the stream ended.

## [0.10.0] - 2026-10-02

### Added

- `streamlord-test`, a new module: `datastarEvents(wire)` reads a Datastar response back into the
  events it carried, and asserts on what a handler meant rather than on the bytes it produced.
  `assertPatchElements`, `assertPatchSignals`, `assertSignal`, `assertNoSignal`, `assertNoEvents`
  and `assertExactly`. Every assertion but the last is satisfied by one matching event and ignores
  the rest, so a test about markup does not go red because a handler started patching a signal.
  `assertSignal` folds the whole stream the way the browser does, so it reports what a signal
  ended up as. It depends on `streamlord-core` and nothing else, and binds no test framework: a
  failure is an `AssertionError`, so kotlin.test, JUnit, Kotest and TestNG all work and none is
  imposed.
- `streamlord-core`: `SseDecoder`, the inverse of `SseEncoder`. `decode(text)` gives the events a
  stream carried and `messages(text)` the raw SSE messages, for a test about a heartbeat comment
  or a resume id. It is held to the same twenty official golden files as the encoder, in both
  directions.
- `streamlord-core`: `mergePatch` moves here from `streamlord-analysis`, into `core.json`. RFC
  7386 is how the protocol defines a signal patch, so anything asking what the browser ends up
  holding has to fold them the same way, and that is no longer the editor tooling's private
  business.

## [0.9.0] - 2026-10-02

### Removed

- `streamlord-core`: `Flow<DatastarEvent>.asSse()`. Of 244 public names across the published
  modules it was the only one with no caller anywhere: not in the tests, the demo, the editors,
  the documentation's examples or the core itself, and no page ever mentioned it. It was one line
  of sugar over `Streamlord.encode(flow)`, which is still there. `explicitApi()` makes you write
  the word `public`; it does not ask whether you meant it.

### Added

- `streamlord-core`: tests for `Streamlord.encode(flow)`, which `asSse` was the only caller of and
  which nothing had exercised: the frames it produces, an empty flow, and that `guardElements`
  stops an eaten expression before it becomes one.

## [0.8.0] - 2026-10-02

### Added

- `streamlord-core`: `StreamAuthorisation`, a check a stream carries for as long as it runs. A
  stream is authorised once and then lives for minutes while the reader logs out or an
  administrator revokes access, and nobody asks again. Pass one where you open the stream and
  Streamlord asks before writing, at the open and then no more often than `every` (five seconds by
  default), so a chatty stream does not cost a database round trip per patch. A refusal runs
  `onRefused` on the still-open stream, so the reader is told rather than dropped, and then ends
  the response rather than letting it fall silent.
- `streamlord-ktor`: `respondDatastar(authorisation = ...)` on both the block and the flow forms.
- `streamlord-spring`: `datastarStream(streamlord, request, authorisation) { }`, likewise.
- `streamlord-core`: `Streamlord.stream(sink, authorisation, block)` runs a stream to the end and
  reports whether it got there. `StreamRefusedException` is what ends a refused one; the adapters
  catch it where they opened the stream.

## [0.7.0] - 2026-10-01

### Changed

- `streamlord-analysis`: `signalDrift` returns a `SignalDriftReport` rather than the issue map
  alone. An empty result means nothing unless the check recognised reads at all, and the report's
  `read` and `declared` sets are how a caller tells a clean project from a check that matched
  nothing. That failure is why this exists: the first version called a 45-file corpus clean
  because its pattern missed every signals class.
- `streamlord-json-kotlinx`: `KotlinxSignalsCodec(strict = ...)` is now `requireNamedSerializers`.
  It never had anything to do with refusing unknown signals, which is the `Json` configuration's
  `ignoreUnknownKeys`, and a flag called `strict` on a signals codec reads as though it did.

### Fixed

- `streamlord-core`: `ExecuteScript`'s documentation offered its attribute map as a place for a
  CSP nonce. Under CSP mode Datastar re-creates every script it patches in and sets the nonce
  itself from the page's, so one passed there is overwritten, and with CSP mode off it does
  nothing. The nonce that matters is the one on the document's `<html>`.

## [0.6.0] - 2026-10-01

### Added

- `streamlord-analysis`: `signalDrift(facts)` reports every signal a handler reads that no markup
  in the project declares, which is the failure that compiles, deploys and then hands the handler
  a default on every request. `collectSignalFacts(src, language)` reads one file for it, and the
  check runs across the set, because the page that declares a signal is almost never the file
  that reads it. Only classes something reads signals into are examined; the drift in the other
  direction is not reported, since a signal nobody reads is harmless.
- `streamlord-analysis`: `collectDeclaredSignals(src, language)`, the declarations alone.
  `collectSignals` is unchanged and is still those plus the properties of `@Serializable` classes.

### Fixed

- `streamlord-analysis`: a `@Serializable` class carrying any modifier before `class` was not
  seen, so `public data class` signal classes, which is what an `explicitApi()` module writes,
  contributed no property names to completion.

## [0.5.0] - 2026-10-01

### Added

- `streamlord-core`: `interpolate(format, vararg values)` writes values into markup at its `%s`
  holes and decides what to do with each by where it landed, not by where it came from. Element
  text and ordinary quoted attributes are escaped. A hole in a `data-*` attribute Datastar reads,
  in a tag name, an attribute name, an unquoted value, or inside a `<script>` or `<style>` is
  refused with `UnsafeInterpolationException`, because escaping cannot make those safe. A number
  or a boolean is admitted into a Datastar attribute, so a server-rendered initial signal needs no
  waiver, and `Trusted(html)` waives both the escaping and the refusals for a fragment you
  rendered yourself.
- `streamlord-core`: `Position`, the five places a value can land, which
  `UnsafeInterpolationException` reports.

### Changed

- `streamlord-core`: `ElementsGuard` now walks markup through the same scanner `interpolate` uses,
  so there is one parser rather than two. Its behaviour is unchanged; it allocates one visitor per
  call where it previously allocated none.

## [0.4.0] - 2026-10-01

### Added

- `streamlord-core`: `CspNonce` in `core.domain` holds the half of Datastar's CSP mode that is the
  same in every framework: `generate()` for a per-response nonce of 16 bytes from `SecureRandom`,
  `starterPolicy(nonce)` for somewhere to start, `checked(nonce)` for a nonce from a generator of
  your own, and the two header names.
- `streamlord-ktor`: `CspNoncePlugin` generates one nonce per call, writes the
  `Content-Security-Policy` header from it, and hands the same value back through
  `call.cspNonce` for `dataNonce` on the `<html>` element. Both ends come from one variable, so
  they cannot drift apart, which until now nothing enforced. It is route-scoped, so an
  application that serves documents from some routes and streams from others installs it where
  the documents are. The policy is configurable, `null` writes no header, and `reportOnly = true`
  writes the report-only header instead.
- `streamlord-spring`: `CspNonceFilter` does the same for WebMVC, with `request.cspNonce` to read
  it back. For WebFlux, `exchange.installCspNonce()` writes both ends from inside a `WebFilter`
  you register yourself; the adapter ships no `WebFilter` of its own, because one would put
  Reactor on its compile classpath.

## [0.3.1] - 2026-09-29

### Added

- `streamlord-json-kotlinx`: `KotlinxSignalsCodec` takes the serializers it should use, by the
  type they handle, and consults them before the reflective lookup. A named serializer is the one
  the compiler plugin generated, resolved at the call site, so it is visible to a GraalVM native
  image, where reading a class to find its serializer cannot work. Passing none keeps the old
  behaviour exactly.
- `streamlord-json-kotlinx`: `strict = true` refuses the reflective lookup for a type that was not
  named, so the omission fails on the JVM with the name of the class instead of inside an image on
  the first request that carries it. It asks only for what an image cannot resolve by itself: your
  own classes, and containers holding them.

### Fixed

- Every module published an empty javadoc jar. The jar now carries the API reference generated
  from the KDoc in the source, so javadoc.io has something to show. Releases up to and including
  0.3.0 are on Maven Central with the empty one and cannot be changed.
- `streamlord-json-kotlinx`: a failure to encode or decode now says when no serializer was named
  for the type, and what to add. It used to say only that the value could not be decoded, which
  sends a reader to look at their JSON rather than at their codec.
- `streamlord-analysis`: a hyphen after a signal is read by Datastar 1.0.4 as part of the name
  (`$foo-bar` is `$['foo-bar']`, `$count-1` is `$['count-1']`; only `$a-$b` is a subtraction).
  The `signal-kebab` issue now says so, covers `$count-1` and the scope-variable cases it
  excused before, and offers the spaced subtraction beside the camelCase signal.

## [0.3.0] - 2026-09-27

### Added

- `streamlord-analysis`: the editor-independent analysis behind the editor tooling, as a Kotlin
  module with no dependency beyond `streamlord-core`. `Analyzer().analyzeKotlin(source)` and
  `analyzeHtml(source)` return every issue with its offset, severity, message, reference link and
  quick fixes: the Kotlin `$` interpolation trap, expression syntax (an in-house JavaScript
  parser, positioned like acorn), unknown and Pro actions, the markup rules of a patch, the
  `data-*` attribute rules, the casing rules and the wire hint. Also there: the Kotlin string
  reader (escapes, templates, multi-dollar literals, an offset map), the call-site scanner, the
  signal and selector collectors, the Ktor and Spring route finder, the Stream Inspector's file
  format and curl export, the SSE parser and the JSON merge patch, the hover documentation and the
  highlighting tokenizer. Tested against the cases of the VS Code extension, so the two editors
  agree.
- `editors/intellij`: the Streamlord plugin for IntelliJ IDEA 2026.1 and newer, Community and
  Ultimate. Inspections with quick fixes, completion, hover documentation, highlighting of the
  Datastar tokens, HTML injection into marker-less HTML strings, live templates, and the Stream
  Inspector as a tool window with saved requests shared with VS Code, variables, gutter icons on
  routes and curl export. Published to the JetBrains Marketplace from CI on an `intellij-v*` tag.

### Fixed

- An expression that opens with `{` is read as the object literal Datastar makes of it
  (`return ({count: 0, name: ''})`), in the VS Code extension and the analysis alike; a script
  parser saw a block and flagged `data-signals="{count: 0, name: ''}"` as a syntax error.

## [0.2.0] - 2026-09-27

### Added

- `streamlord-core`: `ElementsGuard`, the `$` trap hunted in HTML written as a string. It walks
  the tags, finds every `data-*` attribute whose value is an expression and runs it through
  `ExpressionGuard`; the exception names the attribute. `Streamlord(guardElements = true)`
  applies it to every element patch and elements response that leaves through that instance:
  streams, `encode(flow)`, Ktor's `respondDatastar(response)`, Spring's `datastarElements` and
  `asServerSentEvents`. Off by default. The Ktor plugin takes it as
  `install(StreamlordPlugin) { guardElements = true }`. The same guard catches a mistyped
  attribute: a `data-*` name one letter away from a Datastar attribute (`data-onn:click`,
  `data-signal:x`) throws `MistypedAttributeException` naming the attribute it resembles,
  while names further away are taken to be your own and left alone. A bare name without key
  or modifier is only judged when a letter is swapped in a long name (`data-indicater`,
  `data-computer`), so `data-test`, `data-kind`, `data-animated` and `data-effects` pass.
  `<script>` and `<style>` bodies are skipped.
  `ElementsGuard.attributes` lists every Datastar attribute name, bound to the catalog by test.
- `ExpressionGuard` accepts `[]`, `{}`, spread (`...$items`) and leading-dot decimals (`.5`),
  which it refused before as operator-only or dangling, and now catches what an eaten signal
  leaves inside a literal or argument list (`{count: }`, `{n: , m: 1}`, `[, 1]`), a missing
  left operand of `<` and a missing right operand of `+` or `-` (`'Hello, ' + `), which it let
  through before, while `$count ++` with a space, valid JavaScript, is no longer refused.
  `ElementsGuard` decodes the HTML entities kotlinx.html and template engines write
  (`&amp;&amp;`, `&lt;`, `&#x27;`) once, as the browser does, before judging a value; skips
  `<script>`, `<style>`, `<textarea>` and `<title>` bodies; and string and regex literals inside
  an expression (`':)'`, `split(/[,;]/)`, `/(?:a|b)/`) are never read as operators, which
  0.1.1's guard got wrong for `(?` and `(=`. `Streamlord(attributePrefixes = ...)` and
  `ElementsGuard.check(html, prefixes)` take a custom bundle alias, longest matched first.
  `Flow<DatastarEvent>.asSse()` takes the instance too.
- `catalog/datastar-1.0.4.json`: `keyCase` on every keyed attribute (`camel`, `kebab` or
  `raw`), the one place the casing rule is written; the catalog test binds `Casing` to it and
  the extension reads it.
- `@Language("HTML")`, `@Language("JSON")` and `@Language("JavaScript")` on every `elements`,
  `signals` and `script` parameter in core, Ktor and Spring, so IntelliJ injects the right
  language into a literal passed straight in. The annotation's artifact,
  `org.jetbrains:annotations`, is declared `compileOnly`, so nothing new reaches a consumer;
  kotlin-stdlib ships the same artifact anyway.
- README: "Three ways to write markup", covering the DSL, strings (`$$"""..."""`,
  `@Language("HTML")`) and templates as equals, with what each gets from the SDK and the editors.
- `streamlord-html`: `Casing`, the casing rules of Datastar applied so that the name you write
  is the name you get. The browser lowercases attribute names, so the keyed helpers write a
  camelCase name as the kebab-case key Datastar reads back as that name (`dataSignals("fooBar")`
  is `data-signals:foo-bar`, the signal `$fooBar`), adding `__case.camel` for `dataOn` and
  `dataClass` where Datastar's default is kebab, and `__case.pascal` for a leading capital.
  `dataAttr`, `dataStyle`, `dataAnimate` and `dataPersist` write the key in kebab-case. `signal()` and the
  other expression helpers read a kebab-case key the way Datastar names it (`signal("foo-bar")`
  is `$fooBar`). `InvalidSignalNameException` for a blank name or one with whitespace, quotes
  or a character that ends an attribute; `hover:bg-red-500`, `xlink:href` and `--brand` are
  fine as keys, and a reference is JavaScript, so `items[0].name` and `items['sub-total']`
  are fine there. An explicit `case =` is kept, with the key still in kebab-case.

### Changed

- `ExpressionGuard` and `InterpolatedExpressionException` moved from `streamlord-html` to
  `streamlord-core` (`io.github.markusaugust.streamlord.core.domain`), so the string and
  template paths can use them without the DSL. `InterpolatedExpressionException` gained an
  `attribute` property.
- `datastarElements`, `DatastarResponse.toResponseEntity` (Spring), `asServerSentEvents` and
  `DatastarEvent.toServerSentEvent` (Spring WebFlux) take an optional `streamlord` parameter,
  last, for the guard; Spring has no auto-configuration, so pass your bean. `Streamlord` gained the `guardElements`
  constructor parameter, also last. Kotlin callers recompile unchanged; Java callers of the
  full constructor pass one more argument.

### Deprecated

- The `streamlord-html` names `ExpressionGuard` and `InterpolatedExpressionException` remain as
  type aliases of the core ones.

### Fixed

An audit of the DSL against the Datastar 1.0.4 client source and bundles found these; the
protocol layer came through it unchanged.

- `streamlord-html`: `js()`, `set()`, `setAll()`, the fetch options (`headers`, `payload`) and
  the Pro actions (`clipboard()`, `intl()`) write JavaScript literals in single quotes through
  the new `JsLiteral`, as the Datastar documentation does: `@get('/x')`, `$name = 'Gorvek'`,
  `{headers: {'X-Csrf-Token': 't'}}`. The JSON they wrote before survived only the kotlinx.html
  DSL, which escapes double quotes; pasted into a double-quoted attribute in a string or a
  template, it ended the attribute. `dataSignals(vararg)` still writes JSON, which is an
  attribute value only.
- `streamlord-html`: `dataBind`, `dataRef` and `dataIndicator` wrote `__case` on the value form
  (`data-bind__case.kebab="foo"`), where Datastar ignores it, since casing applies to a key
  only. With a `case` they now write the key form (`data-bind:foo__case.kebab`), through
  `Casing.key`; without one they write the value, reading a kebab-case name as Datastar names
  the signal (`dataBind("foo-bar")` is `data-bind="fooBar"`, matching `signal("foo-bar")`).
  The `case` parameter of the object forms `dataSignals("{...}")`, `dataComputed("{...}")`
  and `dataClass("{...}")` was equally inert and is removed; the keys of the object are used
  as written.
- `streamlord-html`: `SignalFilter.regexLiteral` dropped the `RegexOption`s, so
  `Regex("user", IGNORE_CASE)` reached the browser as `/user/`. `IGNORE_CASE`, `MULTILINE`
  and `DOT_MATCHES_ALL` become the `i`, `m` and `s` flags; the options with no JavaScript
  equivalent throw `IllegalArgumentException`. A `/` that the pattern had already escaped is
  no longer escaped twice.
- `streamlord-html`: `setAll()` and `toggleAll()` write `include: /.*/` in front of a filter
  that only excludes, matching the documented signature of the two actions.
- `streamlord-html-pro`: `dataMatchMedia` wrapped the query in bare quotes; a `'` in the query
  broke the expression. It is quoted with `js()`.
- `streamlord-core`: `ExecuteScript`'s `data-effect="el.remove()"` follows the attribute
  prefix, so the aliased bundle gets `data-star-effect` and the script tags are removed as
  intended. The switch is `DatastarAttributes.prefix` in `core.protocol`, and the
  `streamlord-html` object of the same name now sets it.
- `streamlord-core`: `redirect()` navigates from a `setTimeout`, as the official SDKs do, so the
  script element is applied and removed before the page unloads.
- `streamlord-spring`: `datastarStream` and `prepareForSse` take an optional
  `HttpServletRequest`; with it, `Connection: keep-alive` is only set for HTTP/1.1, as the SDK
  specification says and HTTP/2 requires.
- Documentation: the default `filterSignals` exclusion is `/(^|\.)_/`, which also drops nested
  `_` keys such as `user._token`; and Datastar Rocket, the separate beta bundle with a
  web-component API, is stated to be outside what the DSL covers.

## [0.1.1] - 2026-09-26

### Added

- `streamlord-html`: `ExpressionGuard` and `InterpolatedExpressionException`. Every expression
  helper in `streamlord-html` and `streamlord-html-pro` now refuses, at render time, text that
  has the shape a Kotlin string template leaves behind when it interpolates a signal
  (`"$count++"` arriving as `"++"`). The message names the helpers and the `$$"..."` literal.
- README: a section on the `$` trap and the three ways to avoid it.

### Fixed

- CI: the Maven Central publish job no longer fires on `vscode-v*` tags.

## [0.1.0] - 2026-09-26

The first forging.

### Added

- `editors/vscode`: the Streamlord VS Code extension (diagnostics, completions, hover,
  highlighting, Stream Inspector) and `catalog/datastar-1.0.4.json`, the shared source of
  truth verified by `CatalogTest`.

- `streamlord-core`: Datastar 1.0.4 protocol model (`PatchElements`, `PatchSignals`,
  `ExecuteScript`), `SseEncoder` verified against the official SDK golden files, non-SSE
  `DatastarResponse` types, a dependency-free strict JSON parser and writer, the
  `DatastarStream` driving port and the `SseSink`, `SignalsCodec` and `IncomingRequest`
  driven ports, and the `Streamlord` facade with bounded signal reading.
- `streamlord-html`: kotlinx.html DSL for every Datastar attribute, modifier, action and
  expression helper; `patchElements { }` for streams, events and responses.
- `streamlord-ktor`: `respondDatastar`, `respondElements`/`respondSignals`/`respondScript`,
  `readSignals`, the `StreamlordPlugin`, and an in-process run of the official SDK
  conformance server.
- `streamlord-spring`: `datastarStream` over `StreamingResponseBody`, `readSignals` over
  `HttpServletRequest`, `ResponseEntity` helpers, `Flow<DatastarEvent>.asServerSentEvents()`
  for WebFlux, and `ElementPatchModeConverter`.
- `streamlord-json-kotlinx`, `streamlord-json-jackson` (Jackson 3, Spring Boot 4) and
  `streamlord-json-jackson2` (Jackson 2, Spring Boot 3): codec adapters for typed signals.
- `streamlord-html`: `payload` and `responseOverrides` fetch options, `dataOnFetch` with
  `FetchEventType` constants, and `DatastarAttributes.prefix` for the aliased `data-star-*` bundle.
- `streamlord-html-pro`: opt-in helpers for the attribute names and actions of Datastar Pro,
  containing no Pro code. The Spring adapter is compiled against Spring Framework 6.2 and
  tested against 6.2 and 7.0.
