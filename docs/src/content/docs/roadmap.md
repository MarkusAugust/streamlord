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
[`guardElements`](/strings/) is, and nothing here changes an API you already call, with one
exception that says so: [two artifacts get new names](#new-names-for-the-kotlinxhtml-modules).

| Order | Planned | What it changes | What you do today |
|---|---|---|---|
| 1 | [The check for your build, in one line](#the-check-for-your-build-in-one-line) | how much you write to have CI read your markup, and that it reads your templates too | the test that calls `analyzeProject` |
| 2 | [Values from outside, in the DSL](#values-from-outside-in-the-dsl) | what the editors and the check say about `dataText(userName)` | keep request data out of expression attributes |
| 3 | [New names for the kotlinx.html modules](#new-names-for-the-kotlinxhtml-modules) | the coordinates of two artifacts | nothing; the old coordinates will point on |
| 4 | [The front page](#the-front-page) | what a first visit shows of values and of the check | read [Strings](/strings/) and [The analysis](/analysis/) |
| 5 | [Values from outside, in templates](#values-from-outside-in-templates) | what the editors and the check say about a template tag in an expression | keep the tag out of expression attributes |
| 6 | [Replay in the Stream Inspector](#replay-in-the-stream-inspector) | what you can see of a stream after it ran | watch it arrive live |
| 7 | [The reading of markup, before 1.0](#the-reading-of-markup-before-10) | the narrow places where `interpolate` and the editors read markup otherwise than the browser | the notes on [Strings](/strings/) |
| 8 | [Starter templates](#starter-templates) | how long it takes to get a first page running | copy the `demo` module |

The order is the order of the work, not a schedule. The first four are the next release. The last
one waits for 1.0 on purpose: a template that lags the API it demonstrates teaches the wrong thing,
and the API is still moving.

## The check for your build, in one line

The editors catch a mistake as you type, and CI has no editor. The
[test over your own sources](/analysis/#a-test-over-your-own-sources) closes that, and it is five
lines you copy: read the folder, keep the failures, assert there are none, format them yourself.
It also reads `.kt` files alone. Markup in a template or an HTML file is judged in the editor and
never in the build, so the claim that CI gives the editor's verdict holds for one of the three
ways to write markup.

Planned: one function in `streamlord-analysis` that reads the project and fails with every finding
written as a compiler writes them, so the test is its call:

```kotlin sample=none
@Test
fun `markup holds`() = Analyzer().checkProject()
```

`checkProject(strict = true)` fails on what
[`failures(strict = true)`](/analysis/#a-test-over-your-own-sources) keeps. It reads the Kotlin
sources and the markup beside them, the HTML and template files the editors read, each with the
rules the editor gives that kind of file. `analyzeProject` and `failures()` stay as they are.

No Gradle or Maven plugin is planned; [Considered, not prioritised](#considered-not-prioritised)
says why.

### Until then

Write the test from [The analysis](/analysis/#a-test-over-your-own-sources): `analyzeProject` on
`src/main/kotlin`, and assert that `failures()` is empty. For a template or an HTML file, hand its
text to `analyzeHtml` in a test of your own.

## Values from outside, in the DSL

[`interpolate`](/strings/) asks where a value landed, which it can only do because each value is
marked where it stands, or its placeholder left standing in the format. The DSL has neither:
`dataText(expression)` takes a `String` and the call itself is the position, so
`dataText(userName)` is the original hazard with nothing in the way. Markup written as a string is
covered twice, by `interpolate` at run time and by the editors, while the DSL is covered by a
paragraph of advice.

Planned: a diagnostic in `streamlord-analysis`, so it lands in both editors and in the
[check you run from a test](/analysis/), for an expression-valued DSL attribute whose argument is
not a literal, a constant or one of Streamlord's expression helpers: `dataText(userName)`. It is a
warning and no more, because a name may hold an expression you built as well as a value from a
request, and nothing short of the compiler can tell `userName` from `COUNT_TWICE`. A warning fails
no build: `failures(strict = true)` keeps it, and `failures()` does not. IntelliJ, which knows
what a name is set to, says nothing where it can see the expression is yours.

It carries the fix the [templates page](/templates/#do-not-render-a-value-into-an-expression)
shows: the value in an attribute of its own, read with `el.dataset`, or patched into a signal from
the server. Nothing in the SDK changes.

### Until then

Keep request data out of expression attributes. Put it in element text or an ordinary attribute,
where kotlinx.html escapes it for you, and read it with `el.dataset` when an expression needs it.
When a value really has to reach an expression, patch it into a signal from the server rather than
writing it into the markup, which is what signals are for.

## New names for the kotlinx.html modules

`streamlord-html` is the support for the kotlinx.html DSL, one of three ways to write markup, and
its name says it is all of HTML. `streamlord-html-pro` is the same DSL's helpers for Datastar Pro.

Planned: `streamlord-html` becomes `streamlord-kotlinx-html`, and `streamlord-html-pro` becomes
`streamlord-kotlinx-html-datastar-pro`. The old coordinates are published once more as a
relocation, so a build that names them resolves to the new ones and says so. Packages and imports
do not change. This is the one item on the page that asks something of a build that works today,
and it comes early because it only gets dearer.

### Until then

Nothing. When it lands, change the two coordinates when the build tells you to.

## The front page

The front page tells the story of one mistake that compiles and is caught. It shows neither of the
two things that most change how you write: a value written where it stands in an HTML string,
marked with `+`, and the test that holds a whole project to the editors' verdict where nobody has
an editor open.

Planned: both get a place there, as code that was compiled the morning the page was built, like
the rest of it.

### Until then

[Strings](/strings/#values-from-outside) has the block, and
[The analysis](/analysis/#a-test-over-your-own-sources) has the test.

## Values from outside, in templates

A template has the DSL's gap in another shape. `data-text="'{{ name }}'"` looks like the fix and is
code injection, as [Templates](/templates/#do-not-render-a-value-into-an-expression) measures, and
the editors say nothing about it: the analysis declines to judge a value that holds template
syntax, since it cannot know what the engine will put there.

Planned: the same diagnostic for a template tag inside an attribute the catalog marks as taking an
expression, `data-text="'{{ name }}'"`, with the delimiters the analysis already knows. Silence
where the tag is the whole value of an ordinary attribute, `data-value="{{ name }}"`, which is the
form the templates page recommends. That puts the three ways of writing markup on the same
footing: the compiler never sees a Datastar expression, so the editors and CI read it instead.

It comes after the DSL's because it is the harder half: the analysis has to say something of a
value it skips today.

### Until then

Keep the tag out of expression attributes. Put the value in an attribute of its own, where your
engine escapes it for HTML, and read it with `el.dataset`:

```html
<span data-value="{{ name }}" data-text="el.dataset.value"></span>
```

Never put the tag in a JavaScript string, `data-text="'{{ name }}'"`: the browser undoes the
engine's HTML escaping before Datastar reads the attribute, so a value with a quote in it is code.

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

## The reading of markup, before 1.0

`interpolate` reads markup as the browser's tokenizer does, in every way a patch may be read, and
takes the strictest. The editors read Kotlin as text. Both have narrow places where they read
otherwise than the browser or the compiler would, each found by testing and none by a report:

- Inside a `<select>` the browser drops start tags such as `<title>` or `<style>` that the reading
  still takes as the start of text, as [Strings](/strings/#values-from-outside) says. Markup you
  write can do this, never a value.
- Rules of the browser's tree builder that the reading does not follow: an end tag ignored because
  another element stands between, `</h1>` closing any heading, a second `<form>` dropped.
- In the block of `interpolate`, an `operator fun String.unaryPlus()` of your own, and an extension
  on `Any` called without a receiver, mean the block's. The editors read text and cannot see it.

Planned: one pass over all of them before 1.0, with the fuzzing against a real HTML parser that
found the first two, so that 1.0 has no known place where the reading is wrong.

### Until then

Leave such tags out of a `<select>`, and in a block write the receiver of a call that needs one.

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

- **A Gradle plugin and a Maven plugin for the check.** An earlier version of this page planned a
  `streamlordCheck` task in each. Set aside: it is two more artifacts to publish and keep in step
  with every release, to save a test that [will be one line](#the-check-for-your-build-in-one-line).
  The test runs where your tests run, in either build tool, with nothing but the analysis on the
  test classpath.
- **A limit on concurrent streams.** An open stream holds a socket and a coroutine for as long as
  it lasts, and a limiter that counts arrivals misses that, since five streams a minute is five
  connections held. But the cap is general server hygiene that Ktor and Spring both have
  facilities for, Streamlord would only save the wiring, and a registry of who holds what needs a
  counter that comes down on every way out: one miss locks a reader out of their own page. Use
  your framework's rate limiting, keyed by user, and cap how many streams are open at once, not
  how many were opened this minute.
- **Delimiters the analysis has not met.** The analysis knows a closed set of template delimiters,
  which covers most engines. One outside it, Qute with `{name}`, Closure with `{$name}`, Rocker
  with `@name`, gets `signal-name-expected` on an attribute that takes a signal name. A setting
  for your engine's delimiters would cure that one rule, in two editors, for engines nobody has
  reported using. The form that works today gives no finding for any of them:
  `<span data-value="{$name}" data-text="el.dataset.value"></span>`.
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
  [diagnostic above](#values-from-outside-in-the-dsl), with [its other half](#values-from-outside-in-templates), covers all three styles
  without a change to the SDK.
- **Escaping on the way out of the DSL.** kotlinx.html escapes text and attribute values already.
  What it cannot do is refuse a value in an expression position, which is
  [its own entry](#values-from-outside-in-the-dsl) rather than a second escaping layer.

## Open questions

Nothing above is undecided enough to wait for an answer. If you have an opinion on any of it, or
use an engine or a pattern the list sets aside, the
[issue tracker](https://github.com/MarkusAugust/streamlord/issues) is the place for it.
