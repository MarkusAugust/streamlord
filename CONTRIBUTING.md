# Contributing to Streamlord

Issues are always welcome. Pull requests are taken after a conversation, so open an issue first
and say what you have in mind. Contributors sign the [CLA](CLA.md), and
[the licence page](https://streamlord-docs.netlify.app/licence/) has the whole picture.

A security flaw goes through [SECURITY.md](SECURITY.md), never a public issue.

## Repository layout

| Path | What it is |
|---|---|
| `streamlord-*` | the ten published modules |
| `editors/vscode`, `editors/intellij` | the two editor extensions |
| `catalog/` | `datastar-1.0.4.json`, the shared source of truth for both editors |
| `docs/` | this project's documentation site (Astro, Fristil, Datastar) |
| `demo/` | `streamlord-live`, the service behind the site's live examples and search |
| `docs-samples/` | compiles every Kotlin example in the documentation, and generates the wire transcripts |

## Building and releasing

```sh
./gradlew build
```

JDK 21 builds it; the artifacts target JDK 17. `build` also holds the documentation to what
this build actually does. Every Kotlin example is compiled against the modules, and the
dependency trees the pages print are compared with what the build resolves; a page that has gone
stale on either turns the build red. The coordinates and the wire transcripts are rewritten in
place from the project's own version and encoder, and the build says which page it changed so you
can commit it.

Every published module's public surface is written down in `<module>/api/<module>.api`, and `build`
fails when the code no longer matches it. That catches a change that compiles and still breaks
code built against the previous release. After a change you meant, run
`./gradlew updateKotlinAbi` and commit the dump with it.

Releases go to Maven Central from CI only, from a tag `v<version>` on the release branch. The
`publish-maven-central` job checks that the tag matches, then signs and publishes every module
under `io.github.markusaugust.streamlord` through the Central Portal with automatic release.
The VS Code extension has its own tag, `vscode-v<version>`, and the IntelliJ plugin
`intellij-v<version>`. [RELEASING.md](RELEASING.md) has the branch model and every step.

## Running the documentation site locally

Two processes, in two terminals. The site alone is not enough: its search and the wire panel at
the foot of every page are answered by the demo service, and in development they look for it on
`localhost:8080`.

```sh
./gradlew :demo:run
```

```sh
cd docs && bun install && bun run dev
```

The site is then on `http://localhost:4321`. Skip the first command and the pages still render,
but searching does nothing and the wire panel stays empty, which looks like a bug and is not one.
`docs/src/config.ts` is where that address is decided: `localhost:8080` in development, the
deployed service in production, and `PUBLIC_SERVICE_URL` overrides both.

Before pushing, `bun run lint`, `bun run typecheck` and `bun run check:links` in `docs/`, and
`./gradlew :docs-samples:checkDocSamples` from the root, which compiles every Kotlin example the
pages show.
