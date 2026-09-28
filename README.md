# Streamlord

> *The elders of Bonereach asked Gorvek what a warrior wants of a stream.*
> *"That it flows when I say flow. That it stops when I say stop. That it carries my will to
> the far shore, and nothing else."*
> *"And the JavaScript?"*
> *"I do not know the word."*
>
> — Gorvek of Bonereach, who walked out of the Ashfall with iron in his hand

**Streamlord** is a Kotlin SDK for [Datastar](https://data-star.dev) 1.0.4. It speaks the
Datastar Server-Sent Events protocol exactly, reads the signals the browser sends back, and
serves two realms without favour: **Ktor** (the Sword) and **Spring** (the Shield). It is built
on ports and adapters, carries almost no dependencies, and treats every byte from the browser
as the untrusted thing it is.

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
    implementation("io.github.markusaugust.streamlord:streamlord-ktor:0.3.0")
    implementation("io.github.markusaugust.streamlord:streamlord-html:0.3.0")
}
```

There are eight modules and you will never want all of them.
[Install and modules](https://streamlord-docs.netlify.app/install/) explains which and why.

## The editors

A VS Code extension (`MarkusAugust.streamlord`) and an IntelliJ IDEA plugin
(`io.github.markusaugust.streamlord`) read Datastar as a language: diagnostics with quick fixes
inside Kotlin strings and template files, completion, hover, highlighting of the Datastar tokens
only, and a Stream Inspector. See [The editors](https://streamlord-docs.netlify.app/editors/).

## Repository layout

| Path | What it is |
|---|---|
| `streamlord-*` | the eight published modules |
| `editors/vscode`, `editors/intellij` | the two editor extensions |
| `catalog/` | `datastar-1.0.4.json`, the shared source of truth for both editors |
| `docs/` | this project's documentation site (Astro, Fristil, Datastar) |
| `docs-samples/` | compiles every Kotlin example in the documentation, and generates the wire transcripts |

## Building and releasing

```
./gradlew build
```

JDK 21 builds it; the artifacts target JDK 17. `build` also compiles every example in the
documentation and checks that the dependency trees and wire transcripts it prints still match
what the build resolves and encodes.

Releases go to Maven Central from CI only: bump `version` in `gradle.properties`, commit, push
a tag `v<version>`. The `publish-maven-central` job checks that the tag matches, then signs and
publishes every module under `io.github.markusaugust.streamlord` through the Central Portal
with automatic release. The VS Code extension has its own tag, `vscode-v<version>`, and the
IntelliJ plugin `intellij-v<version>`.

## Changelog

[CHANGELOG.md](CHANGELOG.md), and the entries that change how you write code are summarised on
[the changelog page](https://streamlord-docs.netlify.app/changelog/).

## License

MIT. Take it, wield it, and may your streams never buffer.

*Gorvek, Sarn, Gallowmark and every other name in this grimoire are our own
invention. Any resemblance to legends told at other tables is the mead's doing.*
