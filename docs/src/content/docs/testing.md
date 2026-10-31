---
title: "Testing"
description: "Assert the events your endpoint sent, in Ktor, in Spring, without a framework, and on a stream that stays open."
---

> *A blade is not sharp because the smith says so. It is sharp because it has cut.*
>
> Gorvek of Bonereach

One question this page answers: how do I assert that my endpoint sent the events I meant? It
needs no browser.

Catching a Datastar mistake in CI, where nobody has your editor open, is the other half of
testing, and it reads your sources rather than your responses. That is
[the analysis](/analysis/).

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
                    patchElements(
                        "<li>A new head</li>",
                        selector = "#feed",
                        mode = ElementPatchMode.APPEND,
                    )
                    patchSignals("heads" to 13)
                }
            }
        }

        val events = datastarEvents(client.get("/feed").bodyAsText())

        events.assertPatchElements(
            selector = "#feed",
            mode = ElementPatchMode.APPEND,
            containing = "A new head",
        )
        events.assertSignal("heads", 13)
    }
```

Needs `ktor-server-test-host` and `streamlord-test` in `testImplementation`.

### Declare a posted body as JSON

`setBody` with a string declares the body `text/plain`, and `readSignals` refuses that with
`SignalsMediaTypeException`. Declare it `application/json`, as Datastar's client does:

```kotlin sample=test
@Test
fun `a search reads the query it was sent`() =
    testApplication {
        routing {
            post("/search") {
                call.respondText("found " + call.readSignals().string("query"))
            }
        }

        val response =
            client.post("/search") {
                contentType(ContentType.Application.Json)
                setBody("""{"query":"gate"}""")
            }

        assertEquals("found gate", response.bodyAsText())
    }
```

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
    PatchElements(
        "<li>A new head</li>",
        selector = "#feed",
        mode = ElementPatchMode.APPEND,
    ),
    PatchSignals("""{"heads":13}"""),
)
```

When the order is the point and the content is not, `assertEventTypes` asks for the kinds of event
alone, and `assertNoPatchElements` takes the arguments of `assertPatchElements` and fails when a
patch matches them all:

```kotlin sample=statements
val events = datastarEvents(wire)

events.assertEventTypes(PatchElements::class, PatchSignals::class)
events.assertNoPatchElements(selector = "#error")
```

An `ExecuteScript` goes on the wire as the element patch that appends its `<script>` to `body`,
and comes back as a `PatchElements`. So `assertEventTypes` refuses `ExecuteScript::class` rather
than never matching it, and `assertNoPatchElements()` with no arguments fails on a stream that ran
a script.

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

The decoder reads the body the way Datastar's client does, so a test cannot pass on a response the
client would not act on: a last frame with no blank line after it is not an event, a line without a
colon is skipped, and a frame Streamlord's rules refuse fails the test instead of being read
charitably. That includes, on any message, Datastar's or not, an `id` the client could not send
back as `last-event-id` unchanged, by Streamlord's rule (anything but printable ASCII, or a space
at either end) and a `retry` that is not a whole number of milliseconds from 1 to `Int.MAX_VALUE`.
The client takes both from every message it reads. It takes `retry: 0` too, as a reconnect with no
wait whose backoff never grows, and reads `1e3` as a second; Streamlord writes neither, so a test
fails on a stream that does. `LiveDatastarStream` judges the end of a stream the same way.

`datastarEvents` returns a `List<DatastarEvent>`, so your own assertions work on it unchanged and
these are a convenience rather than a cage. `events.messages` has the raw SSE messages too, for a
test about a heartbeat comment or a resume id.

A stream silent from its start gets a `: keep-alive` comment after five seconds by default, and
after that one whenever it has been silent for 30 seconds. They are
messages, not events, so the event assertions never see them. Under `runTest`, virtual time skips
ahead whenever every coroutine waits, so a stream whose block waits on a real thread writes comment
after comment without pause: give such a test `Streamlord(heartbeat = null)`.

A `StreamAuthorisation` verdict ages on the real clock, not the virtual one, so a `delay(every)`
under `runTest` never makes the stream ask again. A test that wants it to ask again waits in real
time, with a short `every`.

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
            patchElements(
                "<p>Ready</p>",
                selector = "#panel",
                mode = ElementPatchMode.INNER,
            )
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
takes a body or a `datastar` query parameter like the real thing. Give a body
`contentType = "application/json"` as well, as Datastar's client does; without it, reading the
signals throws `SignalsMediaTypeException`.

### Through the controller, with MockMvcTester

To test the route rather than the stream, with the status, the headers and the
`StreamlordExceptionHandler` answers, go through `MockMvcTester`. Spring Boot 4 configures one for
`@WebMvcTest` and `@AutoConfigureMockMvc`, and `MockMvcTester.of(controller)` builds one without
Boot. It needs `assertj-core` next to `spring-test`.

A stream runs in an async cycle of its own, and the body is empty until the test has waited for
it and dispatched the result. `exchange()` does that once, which is enough for an ordinary
controller. A `suspend` controller runs in one async cycle and its stream in a second, and
`exchange()` returns after the first, while the stream may not have written anything yet. The
helper below dispatches until no cycle is left, and sends the request the way Datastar's client
does. It is yours to copy:

```kotlin sample=spring-test
@RestController
class Feed {
    @GetMapping("/feed")
    fun feed(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): StreamingResponseBody {
        val heads = request.readSignals().int("heads") ?: 0
        return response.datastarStream(request = request) {
            patchElements(
                "<li>A new head</li>",
                selector = "#feed",
                mode = ElementPatchMode.APPEND,
            )
            patchSignals("heads" to heads + 1)
        }
    }
}

private val mvc =
    MockMvcTester.of(listOf(Feed())) {
        it.setControllerAdvice(StreamlordExceptionHandler()).build()
    }

/** The header, and the signals where the client puts them: the query for GET and DELETE. */
private fun MockMvcTester.datastar(
    method: HttpMethod,
    uri: String,
    signals: String? = null,
): MvcTestResult {
    var result =
        method(method)
            .uri(uri)
            .header("Datastar-Request", "true")
            .header("Accept", "text/event-stream, text/html, application/json")
            .apply {
                when {
                    signals == null -> Unit
                    method == HttpMethod.GET || method == HttpMethod.DELETE ->
                        param("datastar", signals)
                    else -> contentType(MediaType.APPLICATION_JSON).content(signals)
                }
            }.asyncExchange()
    while (result.unresolvedException == null && result.request.isAsyncStarted) {
        val started = result.mvcResult
        started.getAsyncResult(10_000)
        result = perform(MockMvcRequestBuilders.asyncDispatch(started))
    }
    return result
}

@Test
fun `the feed appends one head`() {
    val result = mvc.datastar(HttpMethod.GET, "/feed", """{"heads":13}""")

    assertThat(result).hasStatusOk().hasHeader("X-Accel-Buffering", "no")
    val events = datastarEvents(result.response.contentAsString)
    events.assertPatchElements(selector = "#feed", containing = "A new head")
    events.assertSignal("heads", 14)
}
```

The statuses below come from `StreamlordExceptionHandler`, which the tester above is given as
advice. A Boot test sees it when the application imports it, or with
`@Import(StreamlordExceptionHandler::class)` on the test class; without it a bad request is an
unresolved exception, not a status.

Where the controller reads the signals decides when a bad request fails, and so where the test
sees it:

- **Read before `datastarStream`**, as above, signals over the limit or not JSON fail on the
  request itself, and the helper hands back the `413`, `400` or `415` at once.
- **Read inside the stream's block**, they fail on the stream's thread, before its first event.
  The request has started async with a `200` by then; the status comes with the dispatch, and
  the helper hands it back all the same.
- **A failure after the first event** has no status to give: the stream has started, and
  `StreamlordExceptionHandler` throws the exception on, so that a real server cuts the
  connection. `assertThat(result).hasFailed()` passes, and `result.response` throws, events and
  all.

MockMvc never ends a stream on its timeout; that takes a real server, as below. A stream that does
not end on its own fails the helper's wait after ten seconds, so give a test a stream that ends.

#### A header set while the stream runs

Spring Boot prints every MockMvc result as the test runs, even when it shows the print only on a
failure, and it prints the first result of a stream while the stream is still running. A header
added to the response by the stream's thread meanwhile can fail the test with
`ConcurrentModificationException`, in some runs and not others, with nothing wrong in the code
under test. It happens in tests alone: a real server reads no headers while the stream runs.

Every header Streamlord sets is set before the controller returns, with one exception, and there
are two other sources of them. How often each failed, in 2000 requests through `MockMvcTester`
under Spring Boot 4.0.8:

- **`Content-Encoding: gzip`**, set with the stream's first write when the application has
  `Streamlord(compress = true)` and the test's request sends `Accept-Encoding: gzip`, so that a
  failure before it can still be answered with a status. This one failed **600 to 900 times**:
  a test that sends `Accept-Encoding: gzip` to a compressing stream fails about every third run.
  Leave `Accept-Encoding` out of the test's request; MockMvc sends none unless asked to.
- **A filter that adds headers when the response commits** does so on the stream's thread, with
  its first event. Spring Security's `HeaderWriterFilter` does by default, and failed 21 times.
  Tell it to write them as the request arrives, which changes nothing a browser sees:

  ```kotlin sample=none
  // org.springframework.security.config.ObjectPostProcessor
  // org.springframework.security.web.header.HeaderWriterFilter
  http.headers {
      it.withObjectPostProcessor(
          object : ObjectPostProcessor<HeaderWriterFilter> {
              override fun <O : HeaderWriterFilter> postProcess(filter: O): O =
                  filter.apply { setShouldWriteHeadersEagerly(true) }
          },
      )
  }
  ```

  Anything else that writes on commit through Spring Security's `OnCommittedResponseWrapper` is
  the same case.
- **A `suspend` controller that suspends before it calls `datastarStream`** calls it on the thread
  it resumes on, while the test may be printing the result of the first cycle. Every header the
  stream sets races then. It failed once in four runs of 2000.
- **Streamlord 0.13 and earlier** set `Connection: keep-alive` with the first write as well,
  which failed about 400 times in 2000 on a plain stream opened through MockMvc.

Where none of these can be helped, turn the printing off with
`@AutoConfigureMockMvc(print = MockMvcPrint.NONE)`. Nothing is printed then, so a failing test
no longer shows the response it got; `datastarEvents` still prints the stream in its own
failures.

### On WebFlux, with WebTestClient

WebTestClient waits for the flow to complete, and the body it hands back is the wire, frames and
all:

```kotlin sample=spring-test
@RestController
class Feed {
    @GetMapping("/feed")
    fun feed(): ResponseEntity<Flow<ServerSentEvent<String>>> =
        flowOf<DatastarEvent>(
            PatchElements(
                "<li>A new head</li>",
                selector = "#feed",
                mode = ElementPatchMode.APPEND,
            ),
            PatchSignals("""{"heads":14}"""),
        ).asDatastarResponse()
}

@Test
fun `the feed sends a head and the count`() {
    val body =
        WebTestClient
            .bindToController(Feed())
            .build()
            .get()
            .uri("/feed")
            .header("Datastar-Request", "true")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Accel-Buffering", "no")
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

    val events = datastarEvents(body)
    events.assertPatchElements(selector = "#feed", containing = "A new head")
    events.assertSignal("heads", 14)
}
```

## Without a framework at all

When what you are testing is the event rather than the route, skip both. `SseEncoder` is a pure
function from an event to text, and it is the same one both adapters use.

```kotlin sample=test
@Test
fun `a remove carries a selector and no elements`() {
    assertEquals(
        "event: datastar-patch-elements\ndata: selector #row-7\ndata: mode remove\n\n",
        io.github.markusaugust.streamlord.core.protocol.SseEncoder
            .encode(PatchElements.remove("#row-7")),
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
    val request =
        HttpRequest
            .newBuilder(URI(url))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
    http.send(request, HttpResponse.BodyHandlers.discarding())
}

@Test
fun `a click reaches the page that is already open`() =
    runTest {
        val count = MutableStateFlow(0)
        val server =
            embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                routing {
                    get("/count") {
                        call.respondDatastar {
                            count.collect { patchSignals("count" to it) }
                        }
                    }
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

The answer has to start within the timeout, five seconds by default. Ktor's CIO sends the headers
as soon as the response opens; Netty holds them until the first byte of the body, so on Netty a
handler that writes nothing until a command arrives holds `open` until it does, which is one more
reason for a live view to send the current state first. The heartbeat's first comment comes at
five seconds too, so it cannot be counted on to open the stream within the timeout.

Three things it does:

- **Each `await` reads until what it asks for arrives.** `awaitPatchElements` takes the arguments
  of `assertPatchElements` and moves past the patch it returns, so the next one waits for a new
  patch. `awaitSignal` folds the store as the browser does and returns at the first signal patch
  after which it holds the value. `next()` hands over one event at a time. Each keeps its own
  place, so waiting for a signal never skips a patch another wait is still to see.
- **It waits on real time**, five seconds by default, also inside `runTest`. A wait that comes up
  empty, or a stream that ends first, fails with the assertion's own message and the stream it
  read, like every other assertion on this page.
- **`close()` lets go of the connection**, and the server finds the reader gone on its next
  write. `received` keeps everything it read, comments included, for the assertions above.

`open` sends what the browser's `@get` sends: `Datastar-Request: true`, the client's `Accept`, and
the page's signals as the `datastar` query parameter, `{}` unless you pass `signals`, so a handler
that reads signals sees an object, never none. It takes `headers` for a cookie or a token. The
headers the server answered with are in `responseHeaders`, for `X-Accel-Buffering` or a header of your own;
`open` returns only on a `200 text/event-stream`, and fails on anything else with the answer's
headers and the start of its body. It uses the JDK's own HTTP client, so it binds no HTTP library
either. `LiveDatastarStream.of(reader, onClose)` reads from the `Reader` any other client hands
you; pass the underlying stream as `onClose`, since an `InputStreamReader` waits for a read in
progress before it closes.

## The guard, at runtime

[The analysis](/analysis/) reads source text. `ElementsGuard` reads the HTML an event is actually
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

The same layers, the analysis included, if you want a working example larger than a page.
`SseEncoder` is held to the official Datastar SDK golden files, the Ktor adapter runs the
conformance server in process, and the catalog the editors read is bound by test to the attributes the DSL emits, so neither can
drift from the other without a build going red.
