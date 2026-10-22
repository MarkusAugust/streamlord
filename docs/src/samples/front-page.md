<!--
  The Kotlin the front page shows. Not a page of the site: this file lives outside
  src/content/docs so Astro never publishes it, and docs-samples compiles every fence here
  exactly as it compiles the fences of the pages. The front page imports the file as raw text
  and takes each fence by its `name=`, so what the poster shows is code that built this morning.

  The tabs follow one feed from start to finish: built, tested, broken by accident, and caught.
  Every fence from the feed on is a step of that one story, so they share the list and `heads`.
-->

The problem section: four mistakes that compile and ship, and what stops each one.

The dollar Kotlin takes for itself. Not compiled: it is the line the analysis refuses.

```kotlin sample=none name=mistake-dollar
val heads = 13
button { dataOnClick("$heads++") }
```

A patch the client would reject: a mode that needs a selector, without one. It compiles, which
is the point; the SDK throws where the event is built.

```kotlin sample=stream name=mistake-selector
patchElements(
    "<li>A new head</li>",
    mode = ElementPatchMode.APPEND,
)
```

An event written by hand and never flushed. Not compiled: it is half a handler.

```kotlin sample=none name=mistake-flush
writeStringUtf8("event: datastar-patch-signals\n")
writeStringUtf8("data: signals {\"heads\":14}\n\n")
// and no flush()
```

The handler the first panel runs, as streamlord-live runs it at `/feed`:

```kotlin sample=ktor-routing name=feed
get("/feed") {
    val heads = call.readSignals().int("heads") ?: 0
    call.respondDatastar {
        patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
            li { +"A new head hangs on the wall" }
        }
        patchSignals("heads" to heads + 1)
    }
}
```

What a test says about that response from 13, both names streamlord-test's own:

```kotlin sample=assertions name=asserted
events.assertPatchElements(selector = "#feed", mode = ElementPatchMode.APPEND, containing = "A new head")
events.assertSignal("heads", 14)
```

The page the feed lives on, broken by accident: rendered with the count it starts from, and a
button that means the signal and gets the parameter. The editor panel draws this one. Not
compiled as a sample, since it is the mistake; the analysis judges it instead.

```kotlin sample=none name=broken
fun FlowContent.wall(heads: Int) {
    ul { id = "feed" }
    button {
        dataOnClick("$heads++")
        +"Hang a head"
    }
}
```

The test from the testing page that reads a project's own sources:

```kotlin sample=test name=ci-test
private val analyzer = Analyzer()

@Test
fun `no source file interpolates a signal into Datastar`() {
    val offenders =
        java.io.File("src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                val source = file.readText()
                analyzer
                    .analyzeKotlin(source)
                    .filter { it.severity == Severity.ERROR }
                    .map { it.format(file.path, source) }
            }.toList()

    assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
}
```
