---
title: "Changelog"
description: "What each release changed, and what it asks of your code."
---

[CHANGELOG.md](https://github.com/MarkusAugust/streamlord/blob/master/CHANGELOG.md) in the
repository is authoritative and complete. This page carries only what changes how you write code,
with a sentence about what to do.

## Unreleased

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
