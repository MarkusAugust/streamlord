---
title: "Testing"
description: "Assert the events your endpoint sent, and run the $ check in CI where no editor is watching."
---

> *A blade is not sharp because the smith says so. It is sharp because it has cut.*
>
> Gorvek of Bonereach

Two questions this page answers. How do I assert that my endpoint sent the events I meant, and
how do I catch a Datastar mistake in CI, where nobody has my editor open.

Neither needs a browser.

## Assert what the endpoint sent, in Ktor

`testApplication` runs your routes in the same process, and the response body is the wire.
`datastarEvents` reads that body back into the events it carried, so a test says what it means:

```kotlin sample=test
@Test
fun `the feed appends one item and sets the count`() =
    testApplication {
        routing {
            get("/feed") {
                call.respondDatastar {
                    patchElements("<li>A new head</li>", selector = "#feed", mode = ElementPatchMode.APPEND)
                    patchSignals("heads" to 13)
                }
            }
        }

        val events = datastarEvents(client.get("/feed").bodyAsText())

        events.assertPatchElements(selector = "#feed", mode = ElementPatchMode.APPEND, containing = "A new head")
        events.assertSignal("heads", 13)
    }
```

Needs `ktor-server-test-host` and `streamlord-test` in `testImplementation`.

### Keep a streamed test response under 1 MiB on Ktor 3.6.0

The test host in Ktor 3.6.0 hangs on a streamed response of 1 MiB or more, and the test fails
after a minute with `UncompletedCoroutinesError`. Streamed means `respondBytesWriter`,
`respondTextWriter`, and so `respondDatastar`; a plain `respondText` is not affected, and neither
is a real server. It is a Ktor bug, [KTOR-9968](https://youtrack.jetbrains.com/issue/KTOR-9968).
Keep the data in a test small enough that the whole stream stays under 1 MiB; it is rare for a
test to need more than a few events to say what it means.

### An exception in the stream does not fail the test on Ktor 3.6.0

When the block given to `respondDatastar` throws, the test host answers 200 with the events sent
before the exception, and the exception reaches neither your route nor the test. A real server
passes it on and logs it. Make the handler above crash between its two events, and
`assertPatchElements` still passes; `assertSignal("heads", 13)` is what fails, because the signal
never came. So assert the last event you expect as well, or the whole stream with
[`assertExactly`](#every-assertion-ignores-what-it-did-not-ask-about).

### Every assertion ignores what it did not ask about

That is the point of them. Add a signal patch to the handler above and the assertion about markup
stays green, because it was never about the whole stream. The alternative, comparing the entire
body as one string, goes red when a patch is reordered or a class changes in the markup, and a
test that cries wolf is a test people stop writing.

When the whole stream genuinely is the point, say so:

```kotlin sample=statements
val events = datastarEvents(wire)

events.assertExactly(
    PatchElements("<li>A new head</li>", selector = "#feed", mode = ElementPatchMode.APPEND),
    PatchSignals("""{"heads":13}"""),
)
```

### Signals read as what the stream left them

`assertSignal` folds every signal patch the way the Datastar client merges one. So a signal set
and then changed reads as its last value, a signal removed with `null` is gone, and
`onlyIfMissing` does not overwrite what was already there, leaf by leaf. Numbers are compared by
value, so `13` and `13.0` are the same signal, as they are in the browser. A nested one is reached
with dots:

```kotlin sample=statements
val events = datastarEvents(wire)

events.assertSignal("heads", 13)
events.assertSignal("address.city", "Thurn")
events.assertNoSignal("draft")
```

### It is a list, and it binds no test framework

The decoder reads the body the way the browser does, so a test cannot pass on a response the
client would not act on: a last frame with no blank line after it is not an event, and a frame the
client would reject fails the test instead of being read charitably.

`datastarEvents` returns a `List<DatastarEvent>`, so your own assertions work on it unchanged and
these are a convenience rather than a cage. `events.messages` has the raw SSE messages too, for a
test about a heartbeat comment or a resume id.

Nothing in `streamlord-test` depends on a test framework. A failure is an `AssertionError`, which
every runner understands, so kotlin.test, JUnit 4 and 5, Kotest and TestNG all work and none of
them is imposed on you. The module's only dependency is `streamlord-core`.

A failure prints the stream, because the first question is always what the handler actually sent:

```
No element patch with selector #missing.

The stream carried 3 events:
  event: datastar-patch-elements | data: selector #feed | data: mode append | data: elements <li>A new head</li>
  event: datastar-patch-signals | data: signals {"heads":12,"open":true}
  event: datastar-patch-signals | data: signals {"heads":13}
```

## The same, in Spring WebMVC

No server at all. `MockHttpServletResponse` from `spring-test` takes the `StreamingResponseBody`
and hands you back the text.

```kotlin sample=test
@Test
fun `the panel patches inner html`() {
    val response = MockHttpServletResponse()

    val body =
        response.datastarStream {
            patchElements("<p>Ready</p>", selector = "#panel", mode = ElementPatchMode.INNER)
        }
    body.writeTo(response.outputStream)

    assertEquals("no-cache", response.getHeader("Cache-Control"))
    assertEquals(
        "event: datastar-patch-elements\n" +
            "data: selector #panel\n" +
            "data: mode inner\n" +
            "data: elements <p>Ready</p>\n\n",
        response.contentAsString,
    )
}
```

`MockHttpServletRequest` does the other direction when you want to check signal reading, and it
takes a body or a `datastar` query parameter like the real thing.

## Without a framework at all

When what you are testing is the event rather than the route, skip both. `SseEncoder` is a pure
function from an event to text, and it is the same one both adapters use.

```kotlin sample=test
@Test
fun `a remove carries a selector and no elements`() {
    assertEquals(
        "event: datastar-patch-elements\ndata: selector #row-7\ndata: mode remove\n\n",
        io.github.markusaugust.streamlord.core.protocol.SseEncoder.encode(PatchElements.remove("#row-7")),
    )
}
```

This is the cheapest test in the set, and usually the one that fails first when you change how
markup is built.

## A stream that stays open

Everything above reads a response that has ended. A live page does not end: it holds a stream
open, a command arrives on another request, and the patch it causes goes down the stream that
was already there. A test that opens a fresh stream after each command passes while that path is
broken, because the fresh stream renders the new state on its own.

`LiveDatastarStream` holds one open and reads it as it arrives. It needs a real server on a real
port, because Ktor's test host hands a streamed response to the client only once the stream has
ended:

```kotlin sample=test
private val http = HttpClient.newHttpClient()

private fun post(url: String) {
    val request = HttpRequest.newBuilder(URI(url)).POST(HttpRequest.BodyPublishers.noBody()).build()
    http.send(request, HttpResponse.BodyHandlers.discarding())
}

@Test
fun `a click reaches the page that is already open`() =
    runTest {
        val count = MutableStateFlow(0)
        val server =
            embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                routing {
                    get("/count") { call.respondDatastar { count.collect { patchSignals("count" to it) } } }
                    post("/increment") {
                        count.value++
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }.start()
        val origin = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"

        try {
            LiveDatastarStream.open(URI("$origin/count")).use { page ->
                page.awaitSignal("count", 0)
                post("$origin/increment")
                page.awaitSignal("count", 1)
            }
        } finally {
            server.stop()
        }
    }
```

Needs `ktor-server-cio`, or the engine you deploy on, next to `streamlord-test`. On Spring Boot,
`@SpringBootTest(webEnvironment = RANDOM_PORT)` gives the real server and `@LocalServerPort` the
port, from a Kotlin test: the class is coroutine API, called inside `runTest` or `runBlocking`.

The answer has to start within the timeout, five seconds by default, and a server sends its
headers with its first flush. A handler that writes nothing until a command arrives holds `open`
until it does, which is one more reason for a live view to send the current state first.

Three things it does:

- **Each `await` reads until what it asks for arrives.** `awaitPatchElements` takes the arguments
  of `assertPatchElements` and moves past the patch it returns, so the next one waits for a new
  patch. `awaitSignal` folds the store as the browser does, reads whatever has already arrived,
  and returns once the signal holds the value. `next()` hands over one event at a time.
- **It waits on real time**, five seconds by default, also inside `runTest`. A wait that comes up
  empty, or a stream that ends first, fails with the assertion's own message and the stream it
  read, like every other assertion on this page.
- **`close()` lets go of the connection**, and the server finds the reader gone on its next
  write. `received` keeps everything it read, comments included, for the assertions above.

`open` sends `Datastar-Request: true` and takes `headers` for a cookie or a token. It speaks the
JDK's own HTTP client, so it binds no HTTP library either; `LiveDatastarStream.of(reader)` reads
from the `Reader` any other client hands you.

## The `$` check, in CI, with no editor open

The editors catch an interpolated signal as you type. CI does not have your editor. The same
analysis is a library, `streamlord-analysis`, and it takes a string:

```kotlin sample=test
@Test
fun `an interpolated signal is an error rather than a surprise in the browser`() {
    val source = """dataOnClick("${'$'}count++")"""

    val issue = Analyzer().analyzeKotlin(source).single { it.code == "kotlin-interpolation" }

    assertEquals(Severity.ERROR, issue.severity)
    assertTrue(issue.fixes.isNotEmpty(), "the analysis offers a fix")
}
```

Point it at your own sources and it becomes a test that no markup in the project ships a
swallowed expression:

```kotlin sample=test
private val analyzer = Analyzer()

@Test
fun `no source file interpolates a signal into Datastar`() {
    val offenders =
        java.io.File("src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file -> analyzer.analyzeKotlin(file.readText()).map { file.name to it } }
            .filter { (_, issue) -> issue.severity == Severity.ERROR }
            .toList()

    assertTrue(
        offenders.isEmpty(),
        offenders.joinToString("\n") { (name, issue) -> "$name: ${issue.message}" },
    )
}
```

`analyzeHtml(source)` does the same for template and HTML files, checking attributes and
expressions; the id and completeness rules belong to `validateMarkup`. Both return a list of
`Issue`, each carrying the range it covers as `start` and `end`, a `severity`, a `code`, a
`message`, its quick fixes and, where the Datastar reference has a page for it, a `link`, which is
exactly what the two editors render.

Add the artifact where your tests can see it:

```kotlin sample=none
dependencies {
    testImplementation("io.github.markusaugust.streamlord:streamlord-analysis:0.11.1")
}
```

## Signals the pages never declare

A handler reads signals into a type. The pages declare them in markup. Nothing holds the two
together, so a rename that reaches one and not the other compiles, deploys, and hands the handler
a default on every request without a word.

`signalDrift` compares them. It wants the whole project, because the page that declares a signal
is almost never the file that reads it:

```kotlin sample=test
@Test
fun `every signal a handler reads is declared by a page`() {
    val facts = buildMap {
        java.io.File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach {
            put(it.path, collectSignalFacts(it.readText(), SourceLanguage.KOTLIN))
        }
        java.io.File("src/main/resources/templates").walkTopDown().filter { it.extension == "html" }.forEach {
            put(it.path, collectSignalFacts(it.readText(), SourceLanguage.HTML))
        }
    }

    val report = signalDrift(facts)

    // An empty result means nothing if the check recognised no reads at all.
    assertTrue(report.read.isNotEmpty(), "no signal reads were found; are the paths right?")
    assertEquals(emptyMap(), report.issues)
}
```

Assert on both. `report.issues` being empty is the good answer only when `report.read` is not, because
a check that matched nothing reports exactly the same empty map as a project in perfect order. That
is not hypothetical: the first version of this check called a 45-file corpus clean because its
pattern for a signals class did not allow a modifier before `class`, so every `@Serializable public
data class` in it was invisible.

Give it every file that declares markup, whatever the markup is written in: Kotlin strings, the
DSL, a template, an Astro page. A file it never sees is a file whose declarations cannot count,
and a missing declaration is what the check reports.

It reports one direction only. A signal the pages declare and nobody reads is harmless by
construction, since it sits in the browser's store and comes back untouched. A signal a handler
reads that nothing declares is always a mistake, and that is the `signal-never-declared` warning,
pointing at the property or the lookup that will never see a value.

Only classes something actually reads signals into are examined, so the rest of your
`@Serializable` classes are left alone.

## The guard, at runtime

`streamlord-analysis` reads source text. `ElementsGuard` reads the HTML an event is actually
carrying, and it runs wherever your tests exercise the endpoint, without you calling it:

```kotlin sample=statements
val streamlord = Streamlord(guardElements = true)
```

An element patch whose `data-*` expression a Kotlin template ate, or whose attribute name is one
letter from a real one, then throws instead of reaching the browser. You can also call it
directly on a string, which is worth doing in the tests of a markup function:

```kotlin sample=test
@Test
fun `the row markup survives the dollar trap`() {
    ElementsGuard.check($$"""<button data-on:click="$count++">Raise</button>""")
}
```

## What this project does

The same three layers, if you want a working example larger than a page. `SseEncoder` is held to
the official Datastar SDK golden files, the Ktor adapter runs the conformance server in process,
and the catalog the editors read is bound by test to the attributes the DSL emits, so neither can
drift from the other without a build going red.
