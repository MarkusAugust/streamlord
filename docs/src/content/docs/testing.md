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
    testImplementation("io.github.markusaugust.streamlord:streamlord-analysis:0.8.0")
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
