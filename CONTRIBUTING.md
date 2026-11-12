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
| `editors/vscode/analysis-js` | the Gradle project `:analysis-js`: the analysis compiled to JavaScript for the VS Code extension; see below |
| `catalog/` | `datastar-1.0.5.json`, the source of truth for the names core, the analysis and both editors read |
| `datastar-reference/` | a local, git-ignored copy of [starfederation/datastar](https://github.com/starfederation/datastar) at the release tag Streamlord speaks, `datastar-reference/datastar-1.0.5/`, fetched by `npm run reference` in `conformance/generate`; the authority for every claim about the client, and what the generators below read |
| `conformance/` | what the client and the servers actually do, as fixtures the tests hold Streamlord to; see below |
| `docs/` | this project's documentation site (Astro, LO-TEK, Datastar) |
| `demo/` | `streamlord-live`, the service behind the site's live examples and search |
| `docs-samples/` | compiles every Kotlin example in the documentation, and generates the wire examples the pages show |

## Building and releasing

```sh
./gradlew build
```

JDK 21 builds it, with Node 24 for the tests that also run as JavaScript; the artifacts target JDK 17. `build` also holds the documentation to what
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

## One analysis, two editors

The checks both editors show are `streamlord-analysis`, with the part of `streamlord-core` that
reads what the client reads. The IntelliJ plugin calls it on the JVM. The VS Code extension runs
the same sources compiled to JavaScript by `:analysis-js`, an unpublished Kotlin/JS project in
`editors/vscode/analysis-js`, and reaches it through one driving port, `Port.kt` and
`InspectorPort.kt` there: text and plain values in, plain objects out, with TypeScript types the
compiler writes. A check is written and tested once, in Kotlin. Completion and hover are not there
yet: what to offer and what to say is decided in each editor's adapter, and `nameAtEnd` stands in
both, in `completion.ts` and in `StreamlordCompletionContributor.kt`.

- **Tests.** `./gradlew build` runs the tests of the analysis and of core's client part twice, on
  the JVM and on Node, so it needs Node 24 beside JDK 21. A test reads the catalog, `conformance/`
  and the cases under `editors/vscode/test/fixtures` through `fixtureText` and `fixtureNames`,
  which each platform has its own few lines of. A test whose subject or oracle is the JVM is named
  `*JvmTest` and runs there alone. Node and npm are the ones installed; what Kotlin/JS fetches to
  run the tests is locked in `kotlin-js-store/package-lock.json`, which
  `./gradlew kotlinUpgradePackageLock` rewrites when a Kotlin upgrade changes it.
- **What the JVM alone has.** The shared sources use the Kotlin standard library, and of `java.*`
  only two names their public signatures show, `BigDecimal` and `File`, which `:analysis-js`
  declares as empty classes. What a module asks of the JVM stands in its `PlatformJvm.kt` (core has
  a second in `client`, for what the analysis asks too), and `:analysis-js` has the same
  declarations for JavaScript; a new one is added to both.
- **Patterns.** Every regular expression is built with `regex(...)`, never `Regex(...)`. On the
  JVM the two are the same. For JavaScript, `regex` respells the pattern so that it means what
  java.util.regex means by it, since the engines read `\s`, `$`, `.` and `(?i)` differently, and
  refuses a construct it has no spelling for. A whole input is asked for with `matchEntire`, never
  `matches`, which Kotlin/JS answers from the first match alone. `PatternsTest` holds what each
  construct means, on both engines, and `PatternsInMainTest` that every pattern in the shared
  sources goes that way.
- **The extension.** `npm run build`, `npm test` and `npm run typecheck` in `editors/vscode` start
  with `npm run analysis`, which asks Gradle for the library and is quick when no Kotlin changed.

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
tests therefore do not trust a rule as Streamlord writes it; they compare it with what
the client itself does, recorded in `conformance/`:

| Path | What it holds | Made by |
|---|---|---|
| `conformance/client/` | the client's own functions run over fixed inputs: casing, attribute keys, expression rewriting, modifier tags, the signal store, what each attribute plugin declares, and the options each backend action reads | `conformance/generate/client.mjs`, from `datastar-reference/` |
| `conformance/dom/` | the client run in a browser-like DOM: what each SSE frame did to a page and its store, what the HTML parser does with a script body and where it ends a comment | `conformance/generate/dom.mjs`, from `datastar-reference/` |
| `conformance/dsl/` | what the client did with the HTML each DSL call writes: the listeners, timers, store, requests and DOM afterwards, held to what the call means | the HTML by `./gradlew :streamlord-html-pro:updateClientReadings`, the reading by `conformance/generate/dsl.mjs`, from `datastar-reference/` |
| `conformance/wire/` | the exact HTTP answer each adapter gives, per server, for a fixed set of requests, and in `kotlin/` the request set and the comparison both adapters' tests compile | the adapter tests, with `./gradlew :streamlord-ktor:updateTranscripts :streamlord-spring:updateTranscripts` |

The generators read the free client in `datastar-reference/datastar-<version>/` and nothing
else: `client.mjs` the sources under `library/src`, `dom.mjs` and `dsl.mjs` the built bundles, each
through what `common.mjs` holds for all three. The
directory is git-ignored, and `npm run reference` fills it: `conformance/generate/reference.mjs`
downloads the release tag's archive from GitHub, refuses it unless its sha256 is the one written in
the script, and unpacks it there. The repository holds no Datastar code, only that tag and checksum;
the fixtures themselves are committed, and the tests need only them. Datastar Pro is licensed and is never read, copied or generated from; the catalog
carries only its public names.

The tests run in `./gradlew build`, on the JVM and on Node, and cost seconds. The generators
run by hand, when the reference changes, with `cd conformance/generate && npm install && npm run
reference && npm run client && npm run dom && npm run dsl`. A change to the DSL that changes what
it writes needs `./gradlew :streamlord-html-pro:updateClientReadings` and then `npm run dsl`; the
test says so. A transcript that is meant to change is rewritten with the
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
2. Run the generators: `cd conformance/generate && npm install && npm run client && npm run dom &&
   npm run dsl`. The diff of `conformance/client/`, `conformance/dom/` and the `read` of each case
   in `conformance/dsl/` is the list of rules the client changed. A new attribute, modifier or
   option fails the coverage tests of `DslAsTheClientReadsItTest` and `ProDslAsTheClientReadsItTest`
   until a case reaches it; add the case, then run `./gradlew :streamlord-html-pro:updateClientReadings`
   and `npm run dsl` again.
3. Make the tests green against the new fixtures, changing Streamlord where the client changed and
   the fixtures' inputs where a case no longer applies. Never edit a generated output by hand.
4. Rename `catalog/datastar-<version>.json` and bring its attributes, modifiers, actions and fetch
   options up to date; `CatalogTest` holds the DSL to it. Set `DatastarProtocol.VERSION`, and
   change every place that names the file by its version: `grep -rn 'datastar-<old version>'`
   finds them in the two editors' builds and in the documentation.
5. Run the transcripts and read their diff: `./gradlew :streamlord-ktor:updateTranscripts
   :streamlord-spring:updateTranscripts`, then `./gradlew build`, `npm test` in `editors/vscode` and
   the docs checks above. Note what changed in `CHANGELOG.md`.
