---
title: "Roadmap"
description: "What is planned but not shipped, in the order it will land, and what to do in your own code until each one does."
---

> *A promise is a debt, and I pay mine in iron.*
>
> Gorvek of Bonereach

Nothing on this page has shipped yet; an item leaves it in the release that delivers it. It is
written down so you can see what is coming, decide whether to wait for it, and know what to do
today instead. Everything that touches your code is opt-in when it lands, the way
[`guardElements`](/strings/) is, and nothing here changes an API you already call.

| Order | Planned | What it changes | What you do today |
|---|---|---|---|
| 1 | [Values from outside, in the DSL and in templates](#values-from-outside-in-the-dsl-and-in-templates) | what the editors and CI say about a request value in an expression | keep request data out of expression attributes |
| 2 | [The analysis as a build task](#the-analysis-as-a-build-task) | where CI runs the check | a test that calls `analyzeProject` |
| 3 | [Replay in the Stream Inspector](#replay-in-the-stream-inspector) | what you can see of a stream after it ran | watch it arrive live |
| 4 | [Delimiters the analysis has not met](#delimiters-the-analysis-has-not-met) | a false warning on a correct template file | nothing, for `data-bind` and its kind |
| 5 | [Concurrent stream limits](#concurrent-stream-limits) | how much one reader may hold open | your framework's rate limiting |
| 6 | [Starter templates](#starter-templates) | how long it takes to get a first page running | copy the `demo` module |

The order is the order of the work, not a schedule. The last one waits for 1.0 on purpose: a
template that lags the API it demonstrates teaches the wrong thing, and the API is still moving.

## Values from outside, in the DSL and in templates

[`interpolate`](/strings/) asks where a value landed, which it can only do because the holes are
still standing in the format string. The DSL has no holes: `dataText(expression)` takes a `String`
and the call itself is the position, so `dataText(userName)` is the original hazard with nothing
in the way. A template has the same gap in another shape. `data-text="'{{ name }}'"` looks like
the fix and is code injection, as [Templates](/templates/#do-not-render-a-value-into-an-expression)
measures, and the editors say nothing about it: the analysis declines to judge a value that holds
template syntax, since it cannot know what the engine will put there. So markup written as a
string is covered twice, by `interpolate` at run time and by `unescaped-interpolation` and
`unsafe-interpolation` in the editors, while the DSL and templates are covered by a paragraph of
advice.

Planned: one diagnostic in `streamlord-analysis`, so it lands in both editors and in the
[check you run from a test](/analysis/), as `unsafe-interpolation`. It has two shapes:

- In Kotlin, an expression-valued DSL attribute whose argument is not a literal:
  `dataText(userName)`. A warning, not an error, because a constant looks the same from outside
  and nothing short of the compiler can tell `userName` from `COUNT_TWICE`.
- In a template, a template tag inside an attribute the catalog marks as taking an expression:
  `data-text="'{{ name }}'"`, with the delimiters the analysis already knows. Silence where the
  tag is the whole value of an ordinary attribute, `data-value="{{ name }}"`, which is the form
  the templates page recommends.

Each carries the fix the templates page shows: the value in an attribute of its own, read with
`el.dataset`, or patched into a signal from the server. That puts the three ways of writing
markup on the same footing, the one Streamlord already stands on: the compiler never sees a
Datastar expression, so the editors and CI read it instead. Nothing in the SDK changes.

### Until then

Keep request data out of expression attributes, in the DSL and in templates alike. Put it in
element text or an ordinary attribute, where kotlinx.html or your engine escapes it for you, and
read it with `el.dataset` when an expression needs it. When a value really has to reach an
expression, patch it into a signal from the server rather than writing it into the markup, which
is what signals are for.

## The analysis as a build task

The [check you run from a test](/analysis/#a-test-over-your-own-sources) is one line, but it is
still a test you have to write, in a module that may have no tests, with `streamlord-analysis` on
its test classpath. The editors catch the same findings as you type; CI only does if someone
wrote that test.

Planned: a Gradle plugin and a Maven plugin, each with one task, `streamlordCheck`, that runs
`analyzeProject` over the module's Kotlin sources and fails the build on its `failures()`,
printing each as `path:line:col: severity: message [code]`. Wired into `check` in Gradle and the
`verify` phase in Maven, so it runs where the tests run, with nothing to write. The verdict is
the one the test gives, so the two can live side by side while you move over.

### Until then

Write the test from [Analysis](/analysis/#a-test-over-your-own-sources): `analyzeProject` on
`src/main/kotlin`, and assert that `failures()` is empty.

## Replay in the Stream Inspector

The inspector opens a real Datastar request, exactly as the browser client would, parses the
stream, and keeps every frame it decoded. It also keeps the signal store, applying each
`datastar-patch-signals` with `mergePatchAsClient`, the merge as Datastar's client does it
(RFC 7386 for an ordinary patch, but `onlyIfMissing` leaf by leaf, and an object onto an array
sets its indices), so what it shows is what the client would hold.

What it does not keep is the store at each step. `signals = mergePatchAsClient(signals, patch)`
overwrites, so there is one running value and no way back. When a handler emits fifteen frames in
half a second, you watch the number settle and cannot ask what it was at frame nine.

Planned: a position control over the frames already on screen. Step back, and the store panel shows
what the client would have held at that point. This needs no new capture and no protocol work:
every frame is kept with its arguments, `mergePatchAsClient` is a pure function, so the store at
any index is a fold from the start. The work is the control and the wiring, in both plugins.

## Delimiters the analysis has not met

The analysis carries a set of template delimiter shapes so an engine's own syntax is not judged as
a Datastar expression: `{{ }}`, `{% %}`, `${ }`, `<% %>`, `[# ]`, and JTE's and Velocity's
control-flow keywords. That covers most engines whatever they are called, and it is a closed set
in `Markup.kt` with no way to add to it.

An engine outside it, Qute with `{name}`, Closure with `{$name}`, Rocker with `@name`, has its
expressions judged as Datastar would judge the unrendered text, which was measured rather than
assumed. Attributes that take a signal name report `signal-name-expected` for all three. There
the editors are stricter than the client, which makes a signal named `{$name}` from
`data-bind="{$name}"`: no `$name` can reach such a signal, so a brace or an `@` in a signal name
is almost always a mistake. In the rendered file the tag is gone, and the editor says it is
wrong. In expressions the findings are right about the unrendered text, and a tag does not
belong there in any case;
[Templates](/templates/#do-not-render-a-value-into-an-expression) shows what to write instead.

Planned: `AnalyzeOptions` takes the delimiter pairs your engine uses, and the editors read them
from a setting. Small, and the only reason it is not higher is that it affects one rule for
engines outside a set that already covers most of them.

### Until then

Nothing, for the attributes that take a signal name, which is why this is on the list rather than
in the "considered" pile. A value the page shows goes in an attribute of its own and is read
with `el.dataset`, which gives no findings for any of the three tags and runs nothing the value
holds:

```html
<span data-value="{$name}" data-text="el.dataset.value"></span>
```

That holds where the attribute is in quotes and the engine escapes the value for HTML, and only
where the expression uses the value as text, as `data-text` does, not as a URL or a handler;
[Templates](/templates/#do-not-render-a-value-into-an-expression) has the cases. Never put the
tag in a JavaScript string, `data-text="'{$name}'"`: the browser undoes the engine's HTML
escaping before Datastar reads the attribute, so a value with a quote in it is code. Everything
else about your engine already works, and none of it touches what Streamlord puts on the wire.

## Concurrent stream limits

Framing first, because it matters: this is a sensible default, not a security feature. It will not
stop a determined attacker, who can use more identities. It stops ordinary readers from starving
their own page, which is the common failure.

Opening an SSE stream costs the browser almost nothing. The server holds a socket, a coroutine and
whatever state the stream carries, for as long as it lasts. Ten tabs is ten held connections with
nobody doing anything wrong. Ordinary rate limiting misses this, because a limiter counts arrivals
over time and opening a stream is one request. Five streams a minute is nowhere near any sensible
threshold, and it is five connections held indefinitely. The limiter counts arrivals; the cost here
is occupancy.

Browsers allow roughly six connections per origin on HTTP/1.1, and an open stream occupies one. A
few streams plus normal traffic and the page begins stalling on its own requests, so the browser is
already enforcing a limit well below anything an SDK would set. Over HTTP/2 and HTTP/3 that largely
vanishes, since streams multiplex over one connection. So the limit is off until you turn it on,
like everything else on this page, and it cannot be otherwise: Streamlord does not know what a
reader is until you tell it which identity to count. The value the documentation will tell you to
start with is low, two or three per reader, and documented as low on purpose, or someone on HTTP/2
will hit it and conclude it is arbitrary.

Whether it belongs in Streamlord at all is the open part. Continuous authorisation is at least
SSE-specific, and this is general
server hygiene that Ktor and Spring both have facilities for. The narrow case for it: Streamlord
knows which responses are streams, so it can count the right thing without wiring. That is
convenience, not capability, which is why it sits this low.

It shares less with [continuous authorisation](/operations/) than it looks. That check is per
stream, made inside the stream's own write path, and needs no shared state at all. This one needs a registry of which
streams an identity holds right now, and a counter that decrements on every way out: a clean end, a
disconnect, an exception, a cancelled coroutine. One missed decrement locks a reader out of their
own page for good, and nothing announces it. What the two do share is the clean shutdown path, so
that a client reacts instead of sitting idle.

### Until then

Use your framework's rate limiting, keyed by user rather than by route, and remember that what you
want to cap is how many streams are open at once, not how many were opened this minute.

## Starter templates

After 1.0, not before.

A hypermedia library is judged in the first ten minutes, and ten minutes is about what it takes to
wire Ktor or Spring Boot up from nothing. A repository with a running page, a search box that
answers over the wire, and a "Use this template" button removes that, and "show, don't tell" is
the right instinct.

Planned: one template, not two. Two repositories is two things to keep in step with every release,
and a template that has fallen a version behind teaches the wrong API with full confidence. One
repository covering both adapters, or the `demo` module documented as the thing to copy, carries
the same weight for a fraction of the upkeep.

It waits for 1.0 because the API is still moving. Every release so far has changed the surface,
and each of them would have aged a template silently.

### Until then

`demo` in this repository is a real application that runs the real library: a search answered over
SSE, a stream held open for twelve seconds, the eight patch modes, form validation, and a page
served under a Content Security Policy. It is a module in a Gradle build rather than a template,
so copying it means copying a directory and its build file, but nothing in it is a toy.

## Considered, not prioritised

Weighed and set aside for now, with the reason, so the same ground is not covered twice:

- **A runtime mode that refuses unknown signals.** It would catch the same drift that
  [`signalDrift`](/analysis/#signals-the-pages-never-declare) catches without a request in flight, and it fails every handler the
  moment any page gains a signal. If you want it anyway it is your codec's setting, not ours:
  `KotlinxSignalsCodec(json = Json { ignoreUnknownKeys = false })`.
- **Simulating latency or a dropped connection in the Stream Inspector.** The inspector is a
  client inside the editor, not the browser, so slowing it down says nothing about how the
  Datastar client copes; the browser's own throttling does that properly. Cutting a stream off to
  see whether the *server* notices is the useful half, and the Stop button already does it.
- **An `ElementsRenderer` port.** The 0.1.0 roadmap planned one for 0.3: template engines behind
  an interface, configured once, rather than called at each site. Every release since went past it
  and the argument got weaker each time. It would buy no new ability, only move `pebble.render(...)`
  from the call site into a configuration, and it would cost a module per engine to version and
  keep in step. Worse, it would make the engines we wrote adapters for first-class and everything
  else a thing you glue together yourself, when today any engine works, including one you wrote
  yesterday, because Streamlord only ever wanted a `String`.
- **A typed expression in the DSL.** An earlier version of this page planned that
  expression-valued attributes would stop taking a bare `String` and take a type only the
  expression helpers build, so that `dataText(userName)` would not compile. Set aside: it would
  have rewritten every `dataText("$count * 2")` in every project to guard against a mistake few
  make, broken the one API the documentation leads with, and done nothing for templates, where
  the compiler never sees the markup. The escape hatch for an expression written by hand would
  have had to exist, and the hazard would have lived on inside it. The
  [diagnostic above](#values-from-outside-in-the-dsl-and-in-templates) covers all three styles
  without a change to the SDK.
- **Escaping on the way out of the DSL.** kotlinx.html escapes text and attribute values already.
  What it cannot do is refuse a value in an expression position, which is
  [its own entry](#values-from-outside-in-the-dsl-and-in-templates) rather than a second escaping layer.

## Open questions

One thing is genuinely undecided: what a concurrent stream limit should do at the limit. Refusing
the new stream is safer and breaks the innocent case, where a reader opens a fresh tab and it
quietly does not work; closing the oldest favours the tab they are looking at. Everything else
above is decided enough to build.

If you have an opinion on any of it, the
[issue tracker](https://github.com/MarkusAugust/streamlord/issues) is the place for it.
