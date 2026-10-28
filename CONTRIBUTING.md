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
| `datastar-reference/` | a local, git-ignored copy of [starfederation/datastar](https://github.com/starfederation/datastar) at the release tag Streamlord speaks, `datastar-reference/datastar-1.0.4/`, fetched by `npm run reference` in `conformance/generate`; the authority for every claim about the client, and what the generators below read |
| `conformance/` | what the client and the servers actually do, as fixtures the tests hold Streamlord to; see below |
| `docs/` | this project's documentation site (Astro, Fristil, Datastar) |
| `demo/` | `streamlord-live`, the service behind the site's live examples and search |
| `docs-samples/` | compiles every Kotlin example in the documentation, and generates the wire examples the pages show |

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

## Holding Streamlord to Datastar

Every rule Streamlord has about Datastar is a transcription, and a transcription drifts. The
tests therefore do not trust a rule written in Kotlin or TypeScript; they compare it with what
the client itself does, recorded in `conformance/`:

| Path | What it holds | Made by |
|---|---|---|
| `conformance/client/` | the client's own functions run over fixed inputs: casing, attribute keys, expression rewriting, modifier tags, the signal store | `conformance/generate/client.mjs`, from `datastar-reference/` |
| `conformance/dom/` | the client run in a browser-like DOM: what each SSE frame did to a page and its store, what the HTML parser does with a script body | `conformance/generate/dom.mjs`, from `datastar-reference/` |
| `conformance/wire/` | the exact HTTP answer each adapter gives, per server, for a fixed set of requests | the adapter tests, with `./gradlew :streamlord-ktor:updateTranscripts :streamlord-spring:updateTranscripts` |

The generators read the free client in `datastar-reference/datastar-<version>/` and nothing
else: `client.mjs` the sources under `library/src`, `dom.mjs` the built `bundles/datastar.js`. The
directory is git-ignored, and `npm run reference` fills it: `conformance/generate/reference.mjs`
downloads the release tag's archive from GitHub, refuses it unless its sha256 is the one written in
the script, and unpacks it there. The repository holds no Datastar code, only that tag and checksum;
the fixtures themselves are committed, and the tests need only them. Datastar Pro is licensed and is never read, copied or generated from; the catalog
carries only its public names.

The tests run in `./gradlew build` and in the VS Code suite, and cost seconds. The generators
run by hand, when the reference changes, with `cd conformance/generate && npm install && npm run
reference && npm run client && npm run dom`. A transcript that is meant to change is rewritten with the
`updateTranscripts` tasks and shows up as a diff in the pull request, as `updateKotlinAbi` does
for the public API.

The adapters are also run against the oldest framework version they promise and the newest they
build against: `testOldestKtor` compiles and runs the Ktor tests on Ktor 3.0, and Dependabot
raises the versions in `gradle/libs.versions.toml` on the release branch, so each of its pull
requests runs the same suite on the newest release.

### Moving to a new Datastar version

1. Set `VERSION` and `SHA256` in `conformance/generate/reference.mjs` to the new release tag and
   the sha256 of its archive (`curl -sL <archive> | shasum -a 256`), remove the old
   `datastar-reference/datastar-<version>/`, and run `npm run reference` in
   `conformance/generate`: the generators take the version from the one directory they find, and
   the script refuses to add a second.
2. Run the generators: `cd conformance/generate && npm install && npm run client && npm run dom`.
   The diff of `conformance/client/` and `conformance/dom/` is the list of rules the client changed.
3. Make the tests green against the new fixtures, changing Streamlord where the client changed and
   the fixtures' inputs where a case no longer applies. Never edit a generated output by hand.
4. Rename `catalog/datastar-<version>.json` and bring its attributes, modifiers, actions and fetch
   options up to date; `CatalogTest` holds the DSL to it. Set `DatastarProtocol.VERSION`, and
   change every place that names the file by its version: `grep -rn 'datastar-<old version>'`
   finds them in the two editors' builds and in the documentation.
5. Run the transcripts and read their diff: `./gradlew :streamlord-ktor:updateTranscripts
   :streamlord-spring:updateTranscripts`, then `./gradlew build`, the VS Code suite and the docs
   checks above. Note what changed in `CHANGELOG.md`.
