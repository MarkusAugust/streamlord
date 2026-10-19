<!--
  The Kotlin the front page shows. Not a page of the site: this file lives outside
  src/content/docs so Astro never publishes it, and docs-samples compiles every fence here
  exactly as it compiles the fences of the pages. The front page imports the file as raw text
  and takes each fence by its `name=`, so what the poster shows is code that built this morning.
-->

The handler the first panel runs, as streamlord-live runs it at `/feed`:

```kotlin sample=ktor-routing name=feed
get("/feed") {
    call.respondDatastar {
        patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
            li { +"A new head hangs on the wall" }
        }
        patchSignals("heads" to 13)
    }
}
```

What a test says about that response, both names streamlord-test's own:

```kotlin sample=assertions name=asserted
events.assertPatchElements(selector = "#feed", mode = ElementPatchMode.APPEND, containing = "A new head")
events.assertSignal("heads", 14)
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
