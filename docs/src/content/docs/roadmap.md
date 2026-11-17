---
title: "Roadmap"
description: "What is planned but not shipped, in the order it will land, and what to do in your own code until each one does."
---

> *A promise is a debt, and I pay mine in iron.*
>
> Gorvek of Bonereach

Nothing on this page has shipped yet; an item leaves it in the release that delivers it. It is
written down so you can see what is coming, decide whether to wait for it, and know what to do
today instead. Nothing here changes an API you already call. Two things reach a build that works
today, and each says so where it stands: [two artifacts get new names](#new-names-for-the-kotlinxhtml-modules),
and two new warnings appear in the editors, which a
[strict check](/analysis/#a-test-over-your-own-sources) fails on. A third, the [compiler plugin](#a-compiler-plugin-for-unmarked-values), reaches only a build
that adds it.

| Order | Planned | What it changes | What you do today |
|---|---|---|---|
| 1 | [The check for your build, in one line](#the-check-for-your-build-in-one-line) | how much you write to have CI read your markup, and that it reads your templates too | the test that calls `analyzeProject` |
| 2 | [A command and the rules, for coding agents](#a-command-and-the-rules-for-coding-agents) | what an agent is told as it writes, and what it reads first | point the agent at `llms.txt`, and have it and CI run the strict test |
| 3 | [Values from outside, in the DSL](#values-from-outside-in-the-dsl) | what the editors and the check say about `dataText(userName)` | keep request data out of expression attributes |
| 4 | [A compiler plugin for unmarked values](#a-compiler-plugin-for-unmarked-values) | whether the compiler warns of a value written without its `+`, or with `strict` refuses it | the strict test, IntelliJ, and `Html` for markup you built |
| 5 | [New names for the kotlinx.html modules](#new-names-for-the-kotlinxhtml-modules) | the coordinates of two artifacts | nothing |
| 6 | [The front page](#the-front-page) | what a first visit shows of values and of the check | read [Strings](/strings/) and [The analysis](/analysis/) |
| 7 | [Values from outside, in templates](#values-from-outside-in-templates) | what the editors and the check say about a template tag in an expression | keep the tag out of expression attributes |
| 8 | [Replay in the Stream Inspector](#replay-in-the-stream-inspector) | what you can see of a stream after it ran | watch it arrive live |
| 9 | [The reading of markup, before 1.0](#the-reading-of-markup-before-10) | the narrow places where markup is read otherwise than the browser or the compiler reads it | the notes on [Strings](/strings/#values-from-outside) |
| 10 | [Starter templates](#starter-templates) | how long it takes to get a first page running | copy the `demo` module |

The order is the order of the work, and nothing has a date.

## The check for your build, in one line

The editors catch a mistake as you type, and CI has no editor. The
[test over your own sources](/analysis/#a-test-over-your-own-sources) closes that, and it is five
lines you copy: read the folder, keep the failures, assert there are none, format them yourself.
It reads `.kt` files only. That covers the DSL and HTML written in Kotlin strings, two of the
three ways to write markup. A template or an HTML file is judged in the editor and never in the
build.

Planned: one function in `streamlord-analysis` that reads a module's sources and fails with every
finding written as a compiler writes them, so the test is one call:

```kotlin sample=none
@Test
fun `markup holds`() = Analyzer().checkProject()
```

`checkProject(strict = true)` fails on what
[`failures(strict = true)`](/analysis/#a-test-over-your-own-sources) keeps. It reads the Kotlin
sources under `src/main` and the markup beside them, the HTML and template files the editors read,
each with the rules the editor gives that kind of file. `analyzeProject` and `failures()` stay as
they are.

It is one way in for every caller. The test, [the command for agents](#a-command-and-the-rules-for-coding-agents)
and both editors read a project through it, so they find the same files and hold them to the same
rules. IntelliJ still says more than the others where it knows a value's type.

No Gradle or Maven plugin is planned; [Considered, not prioritised](#considered-not-prioritised)
says why.

### Until then

Write the test from [The analysis](/analysis/#a-test-over-your-own-sources): `analyzeProject` on
`src/main/kotlin`, and assert that `failures()` is empty. For HTML and template files, walk the
folder yourself and hand each file's text to `analyzeHtml`:

```kotlin sample=none
@Test
fun `no template ships markup the analysis calls an error`() {
    val analyzer = Analyzer()
    val errors =
        java.io.File("src/main/resources/templates")
            .walkTopDown()
            .filter { it.extension in setOf("html", "mustache", "peb") }
            .flatMap { file ->
                val source = file.readText()
                analyzer
                    .analyzeHtml(source)
                    .filter { it.severity == Severity.ERROR }
                    .map { it.format(file.path, source) }
            }.toList()

    assertTrue(errors.isEmpty(), errors.joinToString("\n"))
}
```

A JTE, FreeMarker, Velocity or Razor file has syntax of its own that the analysis must be told of:
give `analyzeHtml` a `Source(text, SourceLanguage.HTML, engine = TemplateEngine.JTE)` in place of
the text.

## A command and the rules, for coding agents

A coding agent writes the mistakes on this site as readily as a person does, and faster: a `$count`
that Kotlin fills in where a signal was meant, [the `$` trap](/dollar-trap/); a value from a request
written into an expression; a value in an HTML string left without the `+` that
[marks it for `interpolate`](/strings/#values-from-outside). Each can compile.

Telling the agent the rules is advice: a page it may not have read, a skill it may not follow.
What does not wait to be followed is what runs whether the agent chooses it or not: the compiler
when it builds, the test when it tests, and CI whatever it does. An agent can take a test or a
build setting out as easily as it wrote them, so CI, and a person reading the change, is where
that shows. And an agent that is told of a mistake as it writes makes fewer of them to stop.

Planned, three things.

**A command.** `streamlord-analysis` gets an entry point that reads a project as
[the one-line check](#the-check-for-your-build-in-one-line) does and writes each finding as JSON:
its code, severity, file, line and column, the message, the link, and the fixes as edits to apply.
It ends with 0 when nothing fails and 1 when something does, and with `--strict` it fails on what
`failures(strict = true)` keeps. It is no new artifact. A project that has the analysis for its
tests already has all the command needs, and runs it with its own build tool, in the module that
has the dependency:

```sh
mvn -q exec:java -Dexec.classpathScope=test \
  -Dexec.mainClass=io.github.markusaugust.streamlord.analysis.Check \
  -Dexec.args=--strict
```

```kotlin sample=none
// build.gradle.kts: the classpath of the tests' dependencies, so the project's own code need not compile
tasks.register<JavaExec>("checkMarkup") {
    classpath = configurations.testRuntimeClasspath.get()
    mainClass.set("io.github.markusaugust.streamlord.analysis.Check")
}
```

```sh
./gradlew -q checkMarkup --args=--strict
```

That is quick enough to run after every edit. **Measured**, on a laptop, with an entry point
written for the measurement, the analysis of 0.23.0, and Kotlin sources of 1 700 and of 17 000
lines. The time is the whole command:

| Run through | 1 700 lines | 17 000 lines |
|---|---|---|
| `java` with the classpath in hand | 193 ms | 548 ms |
| Gradle, its daemon running | 449 ms | 834 ms |
| Maven, `exec:java` | 690 ms | 1 002 ms |
| Gradle, no daemon | 1 860 ms | not measured |

**Feedback as the agent writes.** A coding agent's harness can run a command of yours at set
moments, which Claude Code calls a hook. The documentation will show the few lines that run the
command after each edit and hand the findings to the model, and run it once more before the agent
says it is done. That is feedback and no stop, since the lines are in a settings file the agent
can edit. Other harnesses with such a moment take the same command.

**The rules in one file.** The site already serves [`llms.txt`](/llms.txt), an index of these
pages, and [`llms-full.txt`](/llms-full.txt), every page of what has shipped in one file, built
from the sources of the pages. The second is some 90 000 tokens, more than an agent reads before
it writes. Planned beside them is a short file of the rules alone, linked first from the index:
what the `$` trap is, how a value is marked, where a value must never stand, what throws at run
time, the test that fails a build, and how to read a finding, with the list of codes made from the
analysis and not by hand.

After those, two more ways to call the command. `explain` with a finding's code prints that rule,
with a wrong form and the right one. `--fix` applies a fix only where the analysis says there is
one right answer, a misspelt attribute for one, which is a mark the fixes do not carry today. A fix
that is a choice of yours is printed and never applied: above all one that wraps a value in
[`Trusted`](/strings/#when-you-mean-it-to-be-markup), which says the value is safe.

### Until then

Point the agent at [`llms.txt`](/llms.txt). Put the strict test from
[The analysis](/analysis/#a-test-over-your-own-sources) in the project, tell the agent to run the
tests before it finishes, and have CI run them whatever the agent does.

## Values from outside, in the DSL

[`interpolate`](/strings/#values-from-outside) asks where a value landed, which it can only do
because each value is marked where it stands, or its placeholder left standing in the format. The
DSL has neither: `dataText(expression)` takes a `String` and the call itself is the position, so
`dataText(userName)` writes whatever `userName` holds into an expression the browser runs, with
nothing in the way. Markup written as a string is covered twice, by `interpolate` at run time and
by the editors. The DSL is covered by advice alone.

Planned: a diagnostic in `streamlord-analysis`, so it lands in both editors and in the
[check you run from a test](/analysis/), for an expression-valued DSL attribute whose argument is
not a literal, a constant or one of Streamlord's expression helpers: `dataText(userName)`. It is a
warning with a code of its own. A name may hold an expression you built,
`val twice = "\$count * 2"`, as well as a value from a request, and the text cannot tell the two
apart, so `failures()` lets it through and only `failures(strict = true)` keeps it. IntelliJ knows
the type of a value and whether it is a constant, and says nothing where that shows the
expression is yours.

It carries the fix shown below. Nothing in the SDK changes.

### Until then

Keep request data out of expression attributes. Put the value in a `data-*` attribute of its own,
where kotlinx.html escapes it for you, and read it in the expression with `el.dataset`:

```kotlin sample=none
span {
    attributes["data-name"] = userName
    dataText("el.dataset.name")
}
```

When a value really has to reach an expression, patch it into a signal from the server rather than
writing it into the markup, which is what signals are for.

## A compiler plugin for unmarked values

The analysis reads text. It sees that `$name` stands without its `+` in the block of
`interpolate`, and often cannot see what `name` is: a number, a `String`, markup that was built.
IntelliJ knows the type of every value and judges by it, and the test in the build does not. So
the test is milder than the editor, and it runs only when something runs it. The compiler knows
the types too, and runs on every build.

Planned: a Kotlin compiler plugin that gives the build what IntelliJ has, for the two findings
that need types.

- A `String` that is no constant, written without its `+` in the block of `interpolate`.
- An argument that is no constant, no literal and none of Streamlord's expression helpers, such as
  `signal("count")`, handed to a DSL attribute that takes an expression: `dataText(userName)`.

A type tells a `String` from a number, and not your `String` from a request's. So the plugin warns
of some code that is right, as IntelliJ does, and each finding is a warning that fails nothing.
With the plugin's `strict` option each is an error, and then the compiler refuses the code. It is
a plugin you add to a build yourself, and nothing changes for a build that does not.

It starts with a trial, because three things are not known and each decides its shape:

- whether the analysis can run inside the compiler and be handed the types, so that the plugin
  holds no rules of its own;
- whether the compiler's own switches can make a plugin's warning an error, or the plugin needs
  its option for that;
- how it is added to a Maven build and to a Gradle build with no build plugin of Streamlord's.

What the trial finds goes on this page. One cost is known already: the Kotlin compiler promises a
plugin no stable interface, so the plugin is built for each Kotlin version it supports, and has to
be brought along when a new one arrives.

### Until then

The strict test fails on every unmarked value the text shows in an HTML string, and IntelliJ warns
by type as you write. Declare markup you built as `Html`, and the editors and the test know that
it stands unmarked on purpose. For `dataText(userName)` nothing warns yet:
[keep request data out of expression attributes](#values-from-outside-in-the-dsl).

## New names for the kotlinx.html modules

`streamlord-html` is the support for the kotlinx.html DSL, one of three ways to write markup, and
its name says it is all of HTML. `streamlord-html-pro` is the same DSL's helpers for Datastar Pro.

Planned: the two get names that say what they are.

| Today | After |
|---|---|
| `io.github.markusaugust.streamlord:streamlord-html` | `io.github.markusaugust.streamlord:streamlord-kotlinx-html` |
| `io.github.markusaugust.streamlord:streamlord-html-pro` | `io.github.markusaugust.streamlord:streamlord-kotlinx-html-datastar-pro` |

In the release that renames them the old coordinates are published too, as relocations: a build
that names them at that version resolves to the new ones. After that release only the new names
are published, so the build changes its two coordinates to go further. Packages and imports do
not change. It comes early because it only gets dearer.

### Until then

Nothing. In the release that renames them, change the two coordinates to those in the table.

## The front page

The front page tells the story of one mistake that compiles and is caught. Its CI panel shows a
test written by hand, a walk over the files that keeps the errors, from before `analyzeProject`
existed. And it does not show the block form of `interpolate`, a value written where it stands in
an HTML string and marked with `+`, at all.

Planned: the CI panel shows [the one-line check](#the-check-for-your-build-in-one-line), and the
block form gets a place of its own, both as code that was compiled the morning the page was built,
like the rest of it.

### Until then

[Strings](/strings/#values-from-outside) has the block form, and
[The analysis](/analysis/#a-test-over-your-own-sources) has the test.

## Values from outside, in templates

A template has the DSL's gap in another shape. `data-text="'{{ name }}'"` looks like a safe way to
show a value and is code injection, as
[Templates](/templates/#do-not-render-a-value-into-an-expression) measures, and the editors say
nothing about it: the analysis declines to judge a value that holds template syntax, since it
cannot know what the engine will put there.

Planned: the DSL's diagnostic for a template tag inside an attribute the catalog marks as taking
an expression, `data-text="'{{ name }}'"`, with the delimiters the analysis already knows. Silence
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

It comes after the diagnostics because nothing is wrong without it: it shows more of a stream
that already ran right or wrong.

### Until then

Watch the frames arrive. To see the store at one frame, stop the stream there and send the request
again.

## The reading of markup, before 1.0

`interpolate` reads markup as the browser's tokenizer does, in every way a patch may be read, and
takes the strictest. The editors read Kotlin as text. Each has places where it may read otherwise
than the browser or the compiler would:

- Inside a `<select>` the browser drops start tags such as `<title>` or `<style>` that the reading
  still takes as the start of text, as [Strings](/strings/#values-from-outside) says. Markup you
  write can do this, never a value.
- The browser's tree builder has rules past its tokenizer, for elements that close others or are
  dropped where they stand. The reading follows some of them, and which it misses has not been
  measured.
- In the block form of `interpolate` a `+` is the block's, so an `operator fun String.unaryPlus()`
  of your own is not called there. A call written without a receiver, `toString()` or an
  extension of your own on `Any`, is made on the block. The editors read text and cannot see
  either.

Planned: one pass over all of them before 1.0, measuring the reading against a real HTML parser,
so that 1.0 has no known place where the reading is wrong.

### Until then

Leave `<title>` and `<style>` out of a `<select>`, close every element you open in markup you hand
to `interpolate`, and in a block write the receiver of a call that needs one.

## Starter templates

After 1.0, not before.

A hypermedia library is judged in the first ten minutes, and ten minutes is about what it takes to
wire Ktor or Spring Boot up from nothing. A repository with a running page, a search box that
answers over the wire, and a "Use this template" button removes that.

Planned: one template for both adapters. A template is one more thing to keep in step with every
release, and one that has fallen a version behind teaches the wrong API with full confidence, which
is also why it waits: every release so far has changed the surface.

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
- **A package of its own for the command.** A jar that holds everything, or the analysis as
  JavaScript in an npm package run with `npx`. Set aside: the command is quick enough on the
  classpath a project has. Measured on the 1 700 lines of [the table above](#a-command-and-the-rules-for-coding-agents),
  the analysis as JavaScript in Node took 129 ms, a third of a second less than through Gradle and
  half a second less than through Maven. That is too little to earn one more thing to publish. It
  is the same analysis in either case, so this can be taken up again without loss.
- **An MCP server for agents.** It would offer the check as a tool an agent may call. An agent
  chooses whether to call a tool, as it chooses whether to follow a skill. The compiler, the test
  and CI do not wait for it to choose.
- **A limit on concurrent streams.** An open stream holds a socket and a coroutine for as long as
  it lasts, and a limiter that counts arrivals misses that: five streams a minute is five
  connections held. Streamlord knows which responses are streams, so it could count them, but a
  registry of who holds what needs a counter that comes down on every way out, and one miss locks
  a reader out of their own page. What a page mostly needs is not to starve itself, and
  [terminating HTTP/2 in front of the service](/operations/#six-streams-and-the-page-stalls) does
  that without a cap.
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

One thing is undecided: whether the starter template is a repository of its own, or the `demo`
module with a page on how to copy it. The first has the button, and the second cannot fall behind.

If you have an opinion on any of it, or use an engine or a pattern the list sets aside, the
[issue tracker](https://github.com/MarkusAugust/streamlord/issues) is the place for it.
