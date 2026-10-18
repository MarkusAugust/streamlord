---
title: "Changelog"
description: "What each release changed, and what it asks of your code."
---

[CHANGELOG.md](https://github.com/MarkusAugust/streamlord/blob/master/CHANGELOG.md) in the
repository is authoritative and complete. This page carries only what changes how you write code,
with a sentence about what to do.

## 0.13.0

**Drop `status` from the response helpers.** `respondDatastar`, `respondElements`,
`respondSignals` and `respondScript` on Ktor, and `datastarElements`, `datastarSignals`,
`datastarScript` and `toResponseEntity` on Spring, no longer take one: Datastar's client reads
neither a stream nor a body with any status but 200, so any other made the response do nothing.
Source that passes one, also as the first positional argument of `respondDatastar`, no longer
compiles; delete the argument. Code compiled against 0.12 still links.

**Every stream has a heartbeat now.** A stream silent for 30 seconds carries a `: keep-alive`
comment, and one silent from the start gets its first after five seconds, or after the interval
if that is shorter, on Ktor and on Spring WebMVC, unless you set `heartbeat = null` on the
instance, in the plugin or on one stream. That first comment commits the response, so a block that
waits longer than that and then fails cuts the stream off instead of answering with a status. On
Spring WebMVC the heartbeat asks your `StreamAuthorisation` from an IO thread, without the
request's thread-locals, Spring Security's context among them: read what `allows()` needs before
the stream opens, or it refuses a stream that used to stay open. A test
that calls `Streamlord.stream` under `runTest`, with a block that waits on a real thread, gets
comment after comment while virtual time skips ahead: give it `Streamlord(heartbeat = null)`. A
test that counts `events.messages` counts the comments too. See
[Operations](/operations/#heartbeats) and [Testing](/testing/).

**Pass the block after the parentheses.** `respondDatastar` and `datastarStream` take a
`heartbeat` for one stream, and `datastarStream` a `timeout` and an `executor` as well, so a
lambda or a function reference written inside the parentheses as the last positional argument,
`datastarStream(streamlord, request, authorisation, { … })`, lands on `timeout` or `heartbeat`
and no longer compiles. Write `datastarStream(streamlord, request, authorisation) { … }`, or name it
`block = …`. Java source written against 0.12 no longer compiles either: the new parameters take
a `kotlin.time.Duration`, which Java can only pass as `null`, and a `null` heartbeat means none
at all. Binaries compiled against 0.12 still link.
[Spring WebMVC](/spring-webmvc/#streams-that-stay-open) shows `timeout` and `executor`.

**Signals in a body must be sent as `application/json`.** Datastar's client always does, so pages
change nothing. A client of your own that posts signals without that `Content-Type` now gets
`SignalsMediaTypeException`: set the header, or pass `requireJsonContentType = false`. On Ktor,
map the exception to `415` in `StatusPages`; [Security](/security/#forms-from-other-sites) has
the block and the reason.

**Name signals the way Datastar's client reads them.** Names that reached the client as another
name, or as none, now throw where you write them:

- The pairs form of `dataSignals` camel-cases its keys, as the keyed form does:
  `dataSignals("foo-bar" to 7)` declares `$fooBar`, where it declared `foo-bar`. Read `$fooBar` in
  the page.
- A dotted name in `patchSignals`, `removeSignals` and the pairs of `dataSignals` nests:
  `patchSignals("form.email" to "x")` patches `$form.email`, where it sent one key that
  `$form.email` did not reach. `patchSignals` throws on a name given twice.
- A name that declares a signal is ASCII letters, digits and `_`, in parts joined by `.` or `-`.
  `über`, `a:b`, a `/`, an empty part and a part such as `constructor`, `toString` or `__proto__`
  throw `InvalidSignalNameException`.
- A name that ends in `_` before a modifier, a name in `dataOn` or `dataClass` that mixes capitals
  with a hyphen before a lower-case letter (`fooBar-baz`), a `BindModifiers.events` with a capital
  or a `.`, a `BindModifiers.prop` Datastar cannot give back (`x2y`, `_value`, `my_prop`) and a
  custom property with capitals in `dataStyle` are refused. A class from a library, such as
  `MuiButton-root`, goes in the object form: `dataClass("{'MuiButton-root': ...}")`.

[Casing](/casing/) has the rule the names now follow.

**Durations, retries and ids the browser cannot honour throw.** A modifier duration that is
negative or over `Int.MAX_VALUE` milliseconds, a flag such as `debounceLeading` without its
duration, more than one of `half`, `full` and `threshold` on `dataOnIntersect`, an interval of
zero, and a negative
`retryMaxCount` or `retryScaler` throw where you write them. An event with a `retry` that is not a
whole number of milliseconds from 1 to `Int.MAX_VALUE`, or an `eventId` that is not printable
ASCII or has a space at either end, throws `DatastarEventValidationException`. `SignalFilter`
refuses a Kotlin `Regex` that JavaScript reads otherwise or refuses, such as a possessive
quantifier, `\A` or `\p{...}`: write the pattern as JavaScript would read it.

**A test may go red that used to be green.** `streamlord-test` reads a stream as Datastar's client
does: a `retry` or an `id` the client would not keep, a data line with no space after its key and
signals that are not one JSON object fail the decode, and `assertSignal` no longer looks for a key
holding a dot. The literals the HTML helpers write escape quotes, `&` and `\`, so `'it\'s'` is now
`'it\x27s'`, and `dataSignals` writes `\` as `\u005c`. If a test compared that text, compare the
new; if one fails on a stream, the browser was not doing what the test said.

**The Jackson codecs refuse a missing or `null` primitive.** `{}` into
`data class Delete(val id: Long)` used to reach the handler as `Delete(id=0)`; it is a `400` now.
A Kotlin class needs `jackson-module-kotlin` on the mapper you hand the codec, and without it a
decode throws `SignalsCodecException`: register the module. Text after the JSON value is a `400`
too.

**The Jackson codecs ignore keys your type does not name, even when your mapper refuses them.**
A mapper with `FAIL_ON_UNKNOWN_PROPERTIES` on, as `jacksonObjectMapper()` has it, no longer fails
every request that carries a signal the type does not name; nothing to change unless a test of
yours counted on that failure. `Jackson2SignalsCodec` reads through a copy of the mapper you hand
it, made once, so that mapper keeps its own rules; a mapper of a subclass that does not override
`copy()` cannot be used.

**`Jackson2SignalsCodec` refuses a `null` inside a Kotlin parameter's `List<Int>`.** A page that
sends one gets a `400`; declare the element `List<Int?>` if it may. A mapper set to skip such a
`null` with `setDefaultSetterInfo` no longer does for these parameters: put
`@JsonSetter(contentNulls = Nulls.SKIP)` on the parameter instead. [Signals](/signals/#where-the-codecs-differ)
lists where each codec refuses a `null`.

**`requireNamedSerializers = true` wants a container named whole.** A `List<Signals>` that went
through because `Signals` was named now fails with `SignalsCodecException` until you add
`typeOf<List<Signals>>() to ListSerializer(Signals.serializer())` to `serializers`; naming
`Signals` alone does not reach the list. [Native image](/native-image/#2-name-your-serializers)
shows it.

**On Ktor, a failed stream is cut, so Datastar's client reconnects.** The frames the block sent
before it threw go out first. Tomcat and Jetty ended such a stream as if it had finished, and now
cut it as CIO and Netty do. On Ktor's servlet engine, Streamlord throws a stream's failure only
once the engine's response writer has finished. Catch inside the block what the page should show;
[Ktor](/ktor/#when-the-block-fails) says which engines are cut and shows how to end a
`respondBytesWriter` of your own.

**`StreamlordExceptionHandler` takes the exception in its handlers.** Only if you call
`signalsTooLarge` or `signalsUnreadable` yourself: pass the exception. The 0.12 signatures remain,
hidden, for code compiled against them, and the new `signalsNotJson` answers
`SignalsMediaTypeException` with `415`.

**`streamlord-analysis` changed signatures.** Only if you call it yourself:
`ExpressionValidator.validate`, `MarkupValidator.validateAttributes`, `Requests.parseRequestsFile`,
`MarkupOptions`, `RequestsFile` and `AttributeSpec` take new parameters with defaults, so Kotlin
source compiles, but code compiled against 0.12 does not link until you rebuild it. `keyName` is
gone; it named a signal otherwise than the client does.

## 0.12.0

**Spring Boot 3 is no longer supported.** The Spring adapter is built for Boot 4 (Framework 7.0,
Servlet 6.1), and Streamlord needs kotlinx-coroutines 1.10.2 or newer, which Boot 4 already pins.
On Boot 3, stay on 0.11.1. Ktor applications change nothing.

**Map `SignalsDecodeException` to `400`, not `SignalsCodecException`.** The new subclass is what a
codec throws when the request's signals cannot be read. A mapping of the old type still catches
it, but also turns a value the server could not encode, its own bug, into a `400`. See
[Security](/security/).

**`Requests.pathParams` and `fillPath` are gone** from `streamlord-analysis`, with the dialog they
served. Only if you call them yourself: `Requests.routeUrl` writes a route's URL with a `{{name}}`
for each path parameter, filled from the env file's `params`.

## 0.11.1

**Nothing to change in your code.** The modules are the same as in 0.11.0; this release carries
the documentation: the introduction opens with the same endpoint written by hand and with
Streamlord, and the testing page says where Ktor 3.6.0's test host misleads a test.

## 0.11.0

**The inspector's variables are three, in `.streamlord/env.json`.** Only if you call
`Requests` in `streamlord-analysis` yourself: `parseEnvFile` is gone, read the file with
`parseEnv`, which reports anything but `baseUrl`, `signals` and `headers`, and pass
`mergeVariables(defaultUrl, env)` to `resolveRequest`, which now returns errors and the keys that
are not set instead of a list of missing names. The editors are updated to match.

## 0.10.1

**Spring Boot works again.** On 0.10.0 every `readSignals` and `datastarStream` under Spring Boot
throws `NoSuchMethodError`, because Boot pins an older kotlinx-coroutines than the one that
release was compiled against. Upgrade to this release. If you cannot yet, put
`ext["kotlin-coroutines.version"] = "1.11.0"` in your build.

**Take the `Streamlord` bean through the constructor on Spring.** The samples used to take it as
a parameter of the handler method, where Spring builds a new default instance instead of
injecting yours. If you copied them, your codec and `guardElements` are being ignored. See
[Spring WebMVC](/spring-webmvc/).

**A refused stream stays refused.** A handler that caught the refusal of a `StreamAuthorisation`
and carried on used to keep writing. Every write after a refusal is now refused again. Nothing
to change in your code.

**Return `asDatastarResponse()` from a WebFlux controller.** The bare flow from
`asServerSentEvents()` went out without `Cache-Control: no-cache` and `X-Accel-Buffering: no`,
because a `Flow` cannot carry headers. The new function returns it in a `ResponseEntity` that
does. See [Spring WebFlux](/spring-webflux/).

**`interpolate` refuses more.** A value in `onclick` or another event handler, in `srcdoc`, a
string in `style`, and a `javascript:` URL at the start of `href` or `src` now throw
`UnsafeInterpolationException`. If one of yours throws, the value was reaching something the
browser runs; wrap it in `Trusted` only if you wrote it yourself. See [Strings](/strings/).

**Map a body that will not parse to `400`.** `JsonParseException` and `SignalsCodecException`
are a `500` until you do. See [Security](/security/).

**`dataNonce("")` throws**, as the plugin always did for an empty nonce.

**A test may go red that used to be green.** `streamlord-test` now reads a response the way the
browser does. A frame with no blank line after it is not an event, `mode APPEND` in capitals is
malformed, and `assertSignal` follows the client's merge for `onlyIfMissing`. If a test of yours
fails after upgrading, the browser was not doing what the test said. `assertSignal("heads", 13.0)`
now matches a `13` on the wire.

**Import `ExpressionGuard` and `InterpolatedExpressionException` from `core.domain`.** The
aliases in `streamlord-html`, deprecated since 0.2.0, are gone.

## 0.10.0

**Assert on events, not on strings.** The new `streamlord-test` module reads a response back into
the events it carried: `datastarEvents(body).assertPatchElements(selector = "#feed")` and
`assertSignal("heads", 13)`. Assertions ignore what they did not ask about, so a test survives a
handler gaining an event, and `assertExactly` is there for when the whole stream is the point. It
binds no test framework. See [Testing](/testing/).

**`mergePatch` moved** from `streamlord-analysis` to `streamlord-core`, into `core.json`. Change
the import if you used it; nothing else about it changed.

## 0.9.0

**`asSse()` is gone.** One line of sugar over `Streamlord.encode(flow)`, which stays. It was the
only public name in the whole SDK with no caller anywhere and no page describing it, so nothing
you have written can be using it. If something is, call `encode` directly.

## 0.8.0

**A stream can be asked again whether it may still run.** `StreamAuthorisation` goes where you
open the stream: Streamlord asks at the open and then on an interval, a refusal lets you say
something to the reader through `onRefused` and then ends the response rather than going quiet,
and the check is ordinary Kotlin that never reaches the browser. See [Operations](/operations/).

## 0.7.0

**`signalDrift` now reports what it saw.** It returns a `SignalDriftReport`; assert that
`report.read` is not empty as well as that `report.issues` is, because a check that matched
nothing looks exactly like a project in order. See [Testing](/testing/).

**`KotlinxSignalsCodec(strict = ...)` is now `requireNamedSerializers`.** Same behaviour, a name
that says what it does. Refusing unknown signals was never this flag; that is `ignoreUnknownKeys`
on the `Json` you pass.

**Do not put a CSP nonce on a script event.** Datastar re-creates every script it patches in and
sets the nonce from the page's, so one you pass is overwritten under CSP mode and ignored without
it. The KDoc used to suggest otherwise. See [Security](/security/).

## 0.6.0

**A signal your handler reads that no page declares is now findable.** `signalDrift` in
`streamlord-analysis` takes your whole project, compares the signals handlers read against the
signals markup declares, and reports the ones that will always be a default. It is a test you
write once; see [Testing](/testing/). Signal classes written `public data class` also contribute
to completion again, which an over-strict pattern had excluded.

## 0.5.0

**Values from outside, put into markup safely.** `interpolate("""<li>%s</li>""", name)` fills the
holes you leave and decides what to do with each value by where it landed. Text and ordinary
attributes are escaped; a `data-*` attribute Datastar reads, a tag or attribute name, an unquoted
value and a script body are refused, because escaping cannot save those. `Trusted(...)` waives it
for markup you rendered yourself. See [Strings](/strings/).

## 0.4.0

**The CSP nonce is Streamlord's job now.** `install(CspNoncePlugin)` on Ktor, or a
`CspNonceFilter` bean on Spring WebMVC, generates one nonce per response, writes the
`Content-Security-Policy` header from it, and hands the same value to `call.cspNonce` or
`request.cspNonce` for `dataNonce` on your `<html>`. Nothing used to enforce that the header and
the markup agreed, and when they drift Datastar fails silently. The Ktor plugin is route-scoped,
so an application that serves documents from some routes and streams from others installs it
where the documents are. WebFlux gets `exchange.installCspNonce()` for a `WebFilter` you register
yourself. See [Security](/security/), which links to a page running under a real policy.

## 0.3.1

**Name your serializers for a native image.** `KotlinxSignalsCodec` now takes them by the type
they handle and consults them before the reflective lookup, and `strict = true`, which 0.7.0 renamed to `requireNamedSerializers`, refuses that
fallback so a type you forgot fails on the JVM rather than inside the image. Nothing changes if
you pass none. See [Native image](/native-image/).

**The javadoc jar is no longer empty.** Every module up to 0.3.0 published an empty one, so
javadoc.io had nothing to show for a library whose source is mostly documentation. From this
release it carries the API reference generated from the KDoc.

**A hyphen after a signal is part of the name.** Datastar 1.0.4 reads `$foo-bar` as the signal
`foo-bar`, and `$count-1` as the signal `count-1`, not as subtraction. Only `$a-$b` subtracts.
The editors now say so, and offer both fixes: a camelCase signal name, or the subtraction written
with spaces. See [Casing](/casing/).

## 0.3.0

**`streamlord-analysis`.** The analysis behind the editor tooling, extracted into a Kotlin module
that depends on nothing beyond `streamlord-core`. `Analyzer().analyzeKotlin(source)` and
`analyzeHtml(source)` return the issues they find, each with its range, severity, message and
quick fixes. You can call it yourself, which is worth doing in the tests of your markup
functions. See [The editors](/editors/).

**The IntelliJ plugin**, for IDEA 2026.1 and newer, Community and Ultimate. Inspections,
completion, hover, highlighting, HTML injection into marker-less strings, and the Stream
Inspector as a tool window, sharing saved requests with VS Code.

**Fixed:** an expression opening with `{` is now read as the object literal Datastar makes of it,
so `data-signals="{count: 0}"` is no longer flagged as a syntax error.

## 0.2.0

**`ElementsGuard`, and `guardElements`.** The `$` trap hunted in HTML written as a string. Turn it
on with `Streamlord(guardElements = true)`, or `install(StreamlordPlugin) { guardElements = true }`
on Ktor, and every element patch leaving that instance is walked: an expression an eaten Kotlin
template left behind throws with the attribute named, and a `data-*` name one letter from a real
one throws `MistypedAttributeException`. Off by default. See [Strings](/strings/).

## 0.1.1

**`ExpressionGuard`.** Every expression helper in the DSL refuses, at render time, text with the
shape a Kotlin string template leaves behind when it eats a signal. The message names the helpers
and the `$$"..."` literal. See [The $ trap](/dollar-trap/).

## 0.1.0

The first forging: the protocol and the encoder verified against the official golden files, both
adapters, the kotlinx.html DSL, the codec modules, the VS Code extension, and
`catalog/datastar-1.0.4.json` as the shared source of truth.

## On versioning

Streamlord is 0.x, and the API is still allowed to move. A breaking change is listed here with
its migration in the same entry. At 1.0 the ordinary rule takes over.

The Datastar protocol version is tracked separately and stated on every release. This one speaks
**1.0.4**.
