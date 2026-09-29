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

        assertEquals(
            "event: datastar-patch-elements\n" +
                "data: selector #feed\n" +
                "data: mode append\n" +
                "data: elements <li>A new head</li>\n\n" +
                "event: datastar-patch-signals\n" +
                "data: signals {\"heads\":13}\n\n",
            client.get("/feed").bodyAsText(),
        )
    }
```

That string is the whole contract. It is the same shape the [protocol](/protocol/) page prints,
and those bytes are written there by the encoder during the build rather than typed out, so if
you want the exact frame for an event, that page is generated proof of it.

Needs `ktor-server-test-host` in `testImplementation`.

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
function from an event to bytes, and it is the same one both adapters use.

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
`message`, a `link` into the Datastar reference and its quick fixes, which is exactly what the
two editors render.

Add the artifact where your tests can see it:

```kotlin sample=none
dependencies {
    testImplementation("io.github.markusaugust.streamlord:streamlord-analysis:0.3.1")
}
```

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
