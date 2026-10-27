---
title: "The analysis"
description: "The check your editor runs, as a library: one test over src/main/kotlin, a signal no page declares, and the same verdict behind a route."
---

Datastar lives inside attribute values and inside Kotlin strings, where no compiler looks.
`streamlord-analysis` reads them. It is the check both [editors](/editors/) run as you type,
written once as a Kotlin library, so the same verdict can fail a build in CI where nobody has an
editor open.

It catches what compiles and still does nothing in the browser: a signal Kotlin interpolated, a
modifier written with a dot, an attribute one letter from a real one, a signal a handler reads
that no page declares. The [$ trap](/dollar-trap/) is the best known of them.

Take it where your tests can see it, and nowhere else:

```kotlin sample=none
dependencies {
    testImplementation(
        "io.github.markusaugust.streamlord:streamlord-analysis:0.14.0",
    )
}
```

## A test over your own sources

The editors catch an interpolated signal as you type. CI does not have your editor. The
analysis takes a string:

```kotlin sample=test
@Test
fun `an interpolated signal is an error`() {
    val source = """dataOnClick("${'$'}count++")"""

    val issue =
        Analyzer()
            .analyzeKotlin(source)
            .single { it.code == "kotlin-interpolation" }

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

`format` writes each finding as `src/main/kotlin/Page.kt:12:5: error: ... [kotlin-interpolation]`,
the form IDEs and CI logs turn into a link to the line. `position(source)` gives the line and
column alone.

`analyzeHtml(source)` does for template and HTML files what `analyzeKotlin` does for Kotlin,
checking attributes and expressions; the id and completeness rules belong to `validateMarkup`. Both
return a list of `Issue`, each carrying the range it covers as `start` and `end`, a `severity`, a
`code`, a `message`, its quick fixes and, where the Datastar reference has a page for it, a `link`,
which is exactly what the two editors render.

## Signals the pages never declare

A handler reads signals into a type. The pages declare them in markup. Nothing holds the two
together, so a rename that reaches one and not the other compiles, deploys, and hands the handler
a default on every request without a word.

`signalDrift` compares them. It wants the whole project, because the page that declares a signal
is almost never the file that reads it:

```kotlin sample=test
@Test
fun `every signal a handler reads is declared by a page`() {
    fun files(dir: String, extension: String) =
        java.io.File(dir).walkTopDown().filter { it.extension == extension }

    val facts = buildMap {
        files("src/main/kotlin", "kt").forEach {
            val source = it.readText()
            put(it.path, collectSignalFacts(source, SourceLanguage.KOTLIN))
        }
        files("src/main/resources/templates", "html").forEach {
            val source = it.readText()
            put(it.path, collectSignalFacts(source, SourceLanguage.HTML))
        }
    }

    val report = signalDrift(facts)

    // An empty result means nothing if the check recognised no reads at all.
    assertTrue(report.read.isNotEmpty(), "no reads found; are the paths right?")
    // A page declared from a value whose names only its codec knows counts every read as declared.
    assertEquals(emptySet(), report.declaredByCodec)
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

A page declared from a class, `dataSignals(codec.toJsonValue(SearchSignals()))`, declares that
class's properties when it is `@Serializable`, and the check reads them; it names the class from
the constructor, a type argument, or the type of a variable the file declares. When the class is
not `@Serializable`, as with Jackson, or the file does not say which class it is, as with
`toJsonValue(load())` or a parameter typed `JsonObject`, only the codec knows the names, so every
read counts as declared and the check reports nothing anywhere. `report.declaredByCodec` names
what did that, which is why the test above asserts it is empty. A property counts as declared
even when the page writes it as `null`, which the client does not store, so give a signal the
page must hold a value.

It reports one direction only. A signal the pages declare and nobody reads is harmless by
construction, since it sits in the browser's store and comes back untouched. A signal a handler
reads that nothing declares is always a mistake, and that is the `signal-never-declared` warning,
pointing at the property or the lookup that will never see a value.

Only classes something actually reads signals into are examined, so the rest of your
`@Serializable` classes are left alone.

## The same check, behind a route

The analysis takes a string, so it judges what a reader pastes as readily as what CI reads from
disk. The service behind this site does that at `/judge`, and the route below is the heart of
it: the paste arrives as the `source` signal, is read as a template when it starts with `<` and
as Kotlin otherwise, and the verdict is patched into the page with each fix as a button. The analysis is a lexer and a parser
over the paste, so it runs on `Dispatchers.Default` and leaves the server's threads to the
connections:

```kotlin sample=ktor-routing
post("/judge") {
    val source = call.readSignals().string("source") ?: ""
    if (source.encodeToByteArray().size > 1024) {
        call.respondDatastar {
            patchElements(
                "<p>The judge reads at most 1 KiB of UTF-8.</p>",
                selector = "#verdict",
                mode = ElementPatchMode.INNER,
            )
        }
        return@post
    }

    val issues =
        withContext(Dispatchers.Default) {
            val analyzer = Analyzer()
            if (source.trimStart().startsWith("<")) {
                analyzer.analyzeHtml(source)
            } else {
                analyzer.analyzeKotlin(source)
            }
        }

    call.respondDatastar {
        patchElements(selector = "#verdict", mode = ElementPatchMode.INNER) {
            if (issues.isEmpty()) p { +"Nothing to fix." }
            ul {
                // Each button carries the whole paste again, so the first
                // eight findings are the answer, with two fixes each and
                // eight buttons in all; the rest is a count.
                var buttons = 8
                issues.take(8).forEach { issue ->
                    li {
                        +"${issue.severity.name.lowercase()}: ${issue.message}"
                        issue.fixes.take(minOf(2, buttons)).forEach { fix ->
                            buttons--
                            // set() escapes the fixed text as a JS literal.
                            val fixed = set("source", fix.apply(source))
                            button { dataOnClick(fixed); +fix.title }
                        }
                    }
                }
                if (issues.size > 8) li { +"and ${issues.size - 8} more" }
            }
        }
    }
}
```

A `Fix` is a range replacement, so `fix.apply(source)` is the text the field should hold next, not
`fix.text`, which is the helper call without the line it belongs to. The limit counts bytes of
UTF-8, as the plugin's does, so the two limits are in the same unit.

The page binds the field and asks again whenever `source` changes, so the keyboard and the fix
buttons reach the judge the same way. `data-on-signal-patch` with a filter is what does that, and
the debounce is what keeps a paste from being judged one keystroke at a time:

```html
<section
  data-signals="{source: ''}"
  data-on-signal-patch__debounce.300ms="@post('/judge')"
  data-on-signal-patch-filter="{include: /^source$/}"
>
  <textarea data-bind:source></textarea>
  <div id="verdict"></div>
</section>
```

`data-signals` has to stand before `data-on-signal-patch` on the same element: in the other order
the client sees `source` arrive and posts once on load.

Hold the limit on the server, as above, and set the plugin's `maxSignalsSize` a little above it, so
a body that could never be a legitimate paste is never read into memory: a declared length above
the limit is refused on the headers, and a body that declares none is read up to the limit and no
further. The connection still drains the rest, since the engine keeps it for the next request;
what the limit bounds is the memory, not the bytes on the wire. Read the paste, judge it and
answer; do not log it and do not keep it, in an error message least of all. The `StatusPages`
handlers on [Security](/security/#bounded-input) keep Ktor's own messages out of the body as well.
