# Streamlord

> *The elders of Bonereach asked Gorvek what a warrior wants of a stream.*
> *"That it flows when I say flow. That it stops when I say stop. That it carries my will to
> the far shore, and nothing else."*
> *"And the JavaScript?"*
> *"I do not know the word."*
>
> Gorvek of Bonereach, who walked out of the Ashfall with iron in his hand

**Streamlord** is a Datastar toolchain for Kotlin. Datastar lives in strings your compiler never
reads, and Streamlord checks them anyway: in your editor as you type, when the event is built,
and before a byte reaches the browser.

It comes in two halves. The **SDK** speaks the [Datastar](https://data-star.dev) 1.0.4
Server-Sent Events protocol exactly, reads the signals the browser sends back, and serves two
realms without favour: **Ktor** (the Sword) and **Spring** (the Shield). It is built on ports and
adapters, carries almost no dependencies, and treats every byte from the browser as the untrusted
thing it is. The **editors** read Datastar as a language wherever you write it, and need no part
of the SDK to do it.

```kotlin sample=ktor-routing
get("/feed") {
    call.respondDatastar {
        patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
            li { +"A new head hangs on the wall" }
        }
        patchSignals("heads" to 13)
    }
}
```

## 📖 [Documentation](https://streamlord-docs.netlify.app)

Everything lives there: the protocol, both adapters, the three ways to write markup, the
security model, the editors, and the architecture. Go straight to
[your first stream](https://streamlord-docs.netlify.app/first-stream/) if you would rather
start by writing one.

## Install

Most projects take two artifacts: the adapter for your framework, plus `streamlord-html` for the
kotlinx.html DSL.

```kotlin sample=none
dependencies {
    implementation("io.github.markusaugust.streamlord:streamlord-ktor:0.4.0")
    implementation("io.github.markusaugust.streamlord:streamlord-html:0.4.0")
}
```

There are nine modules and you will never want all of them.
[Install and modules](https://streamlord-docs.netlify.app/install/) explains which and why.

## The editors

A VS Code extension (`MarkusAugust.streamlord`) and an IntelliJ IDEA plugin
(`io.github.markusaugust.streamlord`) read Datastar as a language: diagnostics with quick fixes
inside Kotlin strings and template files, completion, hover, highlighting of the Datastar tokens
only, and a Stream Inspector. See [The editors](https://streamlord-docs.netlify.app/editors/).

## Repository layout

| Path | What it is |
|---|---|
| `streamlord-*` | the nine published modules |
| `editors/vscode`, `editors/intellij` | the two editor extensions |
| `catalog/` | `datastar-1.0.4.json`, the shared source of truth for both editors |
| `docs/` | this project's documentation site (Astro, Fristil, Datastar) |
| `docs-samples/` | compiles every Kotlin example in the documentation, and generates the wire transcripts |

## Building and releasing

```
./gradlew build
```

JDK 21 builds it; the artifacts target JDK 17. `build` also holds the documentation to what
this build actually does: every Kotlin example is compiled against the modules, and the
coordinates, dependency trees and wire transcripts the pages print are rewritten from the
project's own version, resolution and encoder. A page that has gone stale turns the build red.

Releases go to Maven Central from CI only: bump `version` in `gradle.properties`, commit, push
a tag `v<version>`. The `publish-maven-central` job checks that the tag matches, then signs and
publishes every module under `io.github.markusaugust.streamlord` through the Central Portal
with automatic release. The VS Code extension has its own tag, `vscode-v<version>`, and the
IntelliJ plugin `intellij-v<version>`.

## Running the documentation site locally

Two processes, in two terminals. The site alone is not enough: its search and the wire panel at
the foot of every page are answered by the demo service, and in development they look for it on
`localhost:8080`.

```
./gradlew :demo:run
```

```
cd docs && bun install && bun run dev
```

The site is then on `http://localhost:4321`. Skip the first command and the pages still render,
but searching does nothing and the wire panel stays empty, which looks like a bug and is not one.
`docs/src/config.ts` is where that address is decided: `localhost:8080` in development, the
deployed service in production, and `PUBLIC_SERVICE_URL` overrides both.

Before pushing, `bun run lint`, `bun run typecheck` and `bun run check:links` in `docs/`, and
`./gradlew :docs-samples:checkDocSamples` from the root, which compiles every Kotlin example the
pages show.

## Changelog

[CHANGELOG.md](CHANGELOG.md), and the entries that change how you write code are summarised on
[the changelog page](https://streamlord-docs.netlify.app/changelog/).

## License

MIT. Take it, wield it, and may your streams never buffer.

*Gorvek, Sarn, Gallowmark and every other name in this grimoire are our own
invention. Any resemblance to legends told at other tables is the mead's doing.*
