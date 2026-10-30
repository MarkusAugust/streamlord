# Conformance fixtures

What the Datastar client and the servers do, recorded by running the client's own code and the
adapters on real servers, so that every claim Streamlord makes is held to that rather than to a
reading of a source. The fixtures under `client/` are JSON objects whose `$comment` names the
reference file and function that produced them and whose `cases` pair an input with the client's
output; those under `dom/` are arrays, shaped as their section says; those under `dsl/` are
objects whose `cases` each hold a call and its reading; those under `wire/` are text. The tests
that read them live in the module they hold: `streamlord-core`, `streamlord-analysis`,
`streamlord-html`, `streamlord-html-pro`, `streamlord-ktor`, `streamlord-spring` and
`editors/vscode`, each reading the file by a relative path, or by the directory its Gradle build
passes it, and each declaring what it reads as an input of its test task.

The generators under `generate/` read only the free client under
`datastar-reference/datastar-<version>/`, a local, git-ignored copy of the Datastar release tag
that `npm run reference` fetches from GitHub and checks against the sha256 in `reference.mjs`:
`client.mjs` the sources under `library/src`, `dom.mjs` and `dsl.mjs` the built
bundles. Nothing from Datastar Pro goes in here, and a generator refuses to read a
file outside that directory. `common.mjs` is what the three share: where that directory is, the
one function that reads from it, the bundle as a script a happy-dom window runs, and the ASCII
the fixtures are written in.

```sh
cd conformance/generate && npm install && npm run reference && npm run client
```

## client

`generate/client.mjs` bundles the reference TypeScript with esbuild, gives the engine the few
globals it touches at module load, and calls the exported functions. The fixtures under
`client/`:

| File | Reference | What it holds |
| --- | --- | --- |
| `casing.json` | `utils/text.ts`: `kebab`, `camel`, `snake`, `pascal`, `modifyCasing` | each key as the four case functions write it, and as `modifyCasing` reads it with no `__case` (camel and kebab defaults) and with each `__case` value, one or two at a time |
| `attribute-keys.json` | `engine/engine.ts`: `parseAttributeKey` | the plugin name, key and modifiers the client splits an attribute name into, and the signal `modifyCasing` makes of the key |
| `expressions.json` | `engine/engine.ts`: `genRx` | the function body the client compiles from an expression, with `returnsValue` false and true, captured by replacing `Function`; `compiles` says whether the real `Function` took it |
| `tags.json` | `utils/tags.ts`: `tagToMs`, `tagHas`, `tagFirst`; `utils/timing.ts`: `modifyTiming` | what the client reads from a modifier's tags, and a trace of the timers it sets and the calls it makes for each timing modifier of `data-on:click` |
| `signal-store.json` | `utils/paths.ts`: `pathToObj`; `engine/signals.ts`: `mergePatch`, `filtered`; `utils/text.ts`: `isStringTrue` | the object a list of paths becomes and what `Object.prototype` gains on the way, the root after a store is merged and what `filtered` sends of it with the fetch default exclude, and which values read as `true` |
| `plugins.json` | `plugins/attributes/*.ts`: the argument each passes to `attribute()` | each plugin's `requirement`, `returnsValue` and `argNames`, recorded by bundling the plugins with an `@engine` whose `attribute()` records it; `CatalogTest` holds the catalog to them |

The tests are the `ClientOracleTest` of each Kotlin module and `clientOracle.test.ts` in the
editor. Where Streamlord deliberately reads a thing differently, the test says so next to the
case.

## dom

`generate/dom.mjs` runs the built free client, `bundles/datastar.js` of the reference, inside a happy-dom window
through `vm.runInContext`, one fresh window per case, on a page holding
`<div id="wrap"><div id="target">old</div></div>` and the signal `keep: 1`, with
`document.startViewTransition` counting its calls. `fetch` is stubbed to answer the case's SSE
text as `text/event-stream`, and a `data-init="@get(...)"` element sends the request, so the
client's own action, line parser and watchers run. The fixtures under `dom/`:

| File | What it holds |
| --- | --- |
| `decoder-cases.json` | frames: the `argsRaw` the client parsed, the page's body afterwards, the store, and how many view transitions it started |
| `merge-differential.json` | scenarios of signal patches, step by step: the root after each, or `THROWS` and the root once a step threw; a step the Kotlin fold refuses on purpose (`__proto__`) is marked `refused` with what the client kept |
| `store-cases.json` | short scenarios of signal patches, a merge, a `null` that deletes, `onlyIfMissing`, arrays and their `length`, and patches that are not objects, each with what the store holds afterwards |
| `script-escapes.json` | script bodies and their escaped forms, as parse5 reads them inside the client's `<body><template>` wrapper and in the whole-document path, and whether the client's `</body>`, `</head>`, `</html>` tests match. parse5 stands in for the browser's `DOMParser`; the three tests are the client's own regular expressions, applied to the text with its svg elements removed as patchElements.ts applies them |
| `comment-ends.json` | comment openings and endings, and whether what follows them is an element, as parse5 reads them inside the client's wrapper: happy-dom ends `<!-->` and `<!--->` elsewhere than the HTML standard does, so these come from parse5 |

The inputs are read from the fixtures themselves (the `sse` texts and the `steps`) and from a
list at the end of `dom.mjs` for the script and comment bodies; the generator writes everything else, and a
run leaves the files byte for byte as they are until the client changes. The tests are
`SseDecoderAsTheClientReadsTest`, which reads `comment-ends.json` too, `MergePatchAsClientTest` and
`ScriptEscapesAsTheParserReadsTest` in `streamlord-core`, and `mergePatchAsClient.test.ts` in the editor.

```sh
cd conformance/generate && npm install && npm run dom
```

## dsl

What the client does with the HTML Streamlord's DSL writes, held to what each call means.
`conformance/dsl/free.json` holds the calls of the free DSL, `pro.json` those of the Pro DSL. Each
case is one line: its `name`, the `call` (the DSL function, the key and the `case` it was given),
the `prefix`, a `probe` that says how to run it, the `html` the DSL wrote, and the `read`.

The Kotlin tests write everything but the `read`, under
`./gradlew :streamlord-html-pro:updateClientReadings`, keeping the `read` of a case whose HTML is
unchanged. `generate/dsl.mjs` writes the `read` and nothing else:

```sh
cd conformance/generate && npm run dsl
```

It runs each case in a fresh happy-dom window with the built `bundles/datastar.js`, or
`datastar-aliased.js` for the prefix `data-star-`. The case's elements are built from parse5's
tree, attribute by attribute, since happy-dom's own parser leaves most character references in an
attribute value undecoded, and they are in the document before the client starts. The clock is
the script's: a timer is recorded with the wait the client passed, falls due that long after it
was set, and runs when the clock reaches it; an interval runs once. A view transition runs its
update after `startViewTransition` returns, as a browser does. The `read` holds the attributes as
parse5 parsed them, the client's errors, every listener it added with its target and options
(an `AbortSignal` included), its timers and how many of them the clock never reached, its
`IntersectionObserver` options, the requests its fetch sent and whether each was cancelled by the
end, each `probe()` an expression ran, the store before and after the probe's events, every
element's attributes and text at the end, and the view transitions. The header holds the sha256
of the bundles and of `dsl.mjs` with `common.mjs`, and the test refuses a reading other scripts took.

A `probe` may `set` a property on an element before its events, `dispatch` a list of events in
place of one per listener, send each in a `burst`, in `rounds`, `respond` to the fetch (with an
answer, or `fail` for a request that never reaches the server, or `hang` for one never answered),
`eval` a script in the page, such as a signal patch, list the `intersect` entries an observer
sees in place of enter, leave and enter again, or `patch` new elements in through the client's
own `datastar-patch-elements`, as the server sends them. A Pro attribute has no plugin in the free
client: for each name in `proAttributes` the script registers one that records the key, modifiers
and value the engine hands it, and for each name in `proActions` an action that records its
arguments. What Pro then does with them is not read; nothing from Datastar Pro is. A Pro value is
not compiled either, since the client compiles an expression only to run it, so a Pro case holds
how the value is carried, not that it is valid JavaScript.

`DslAsTheClientReadsItTest` and `ProDslAsTheClientReadsItTest` in `streamlord-html-pro` render
each case again and fail when the HTML differs from the fixture, which says to run the two
commands above. Then they hold the `read` to what the case means, written by hand in the client's
terms and never computed with the DSL's own code: the event the client must listen to, the signal
it must store, the class it must set, the wait it must take, the request it must send. Listeners,
timers, requests, errors and the store compare whole, so an extra listener fails too, and so does
a timer the clock never reached. A case that writes an option the client ignores, such as
`responseOverrides`, holds the reading that it changed nothing. Each test
also holds the cases to the catalog: every attribute in each of its forms, every modifier, every
tag of a duration modifier, every value of an enum one, every action, every fetch option and every
Kotlin function must be reached by a case, counted from the attributes the client parsed, with an
exemption list that says why for the few the DSL never writes. `ClientOracleTest` in
`streamlord-analysis` and `clientOracle.test.ts` hold the editors' hint of what a call writes on
the wire to the same cases.

The tests were checked by breaking the DSL on purpose, writing the cases again and letting the
client read them. Each break below turns the cases named red, by what the client did rather than
by the HTML alone:

| The DSL broken so that | Red |
| --- | --- |
| a camelCase or PascalCase key gets no `__case` | the camelCase, PascalCase and acronym events, the camelCase and mixed classes, a PascalCase computed and signal, the svg element, and both coverage tests |
| `dataOn` drops the `__case` it worked out | the camelCase, PascalCase and acronym events, and every flag together |
| debounce writes `.notrailing` for leading and `.leading` for no trailing | debounce leading |
| a duration is written in seconds | every case with a delay, debounce, throttle or interval |
| the threshold is written as a fraction | both threshold cases |
| `dataStyle` keeps a camelCase property | style keyed |
| a regex literal leaves `/` unescaped | the post filtered by an exclude with a slash |
| the prefix is ignored | every aliased case |
| a JavaScript string leaves `'` unescaped | none: `ExpressionGuard` refuses to write the cases, since the broken string leaves a statement that starts with `/` |

Writing `__ifmissing` as `__ifMissing` turns nothing red, and should not: the browser lowercases
an attribute name, so the client reads the same modifier.

## wire

Approved wire transcripts: for a fixed set of requests, the exact HTTP answer each adapter gives,
committed as text and compared with the live answer on every test run. Any drift in a status, a
header or the SSE text is a red test that names the file.

One file per case, `wire/<adapter>/<case>.txt`, for the adapters `ktor-cio`, `ktor-netty`,
`spring-mvc-tomcat` (WebMVC on embedded Tomcat) and `spring-webflux-tomcat` (WebFlux through
Spring's reactive Tomcat adapter). The same handlers answer on every adapter, so the four files
of a case read side by side, with three WebFlux exceptions its test states: every WebFlux
stream is a flow mapped to Spring's `ServerSentEvent`, which Spring's own writer puts on the wire
with no space after the field colon and `charset=UTF-8` on the content type, and in
`stream-sse-fields` with `id:` first and the comment as `:the gate holds`; the `signals-*` cases
read the request through a test-side `IncomingRequest` over the exchange, since the adapter
ships none; and `gzip-stream` is plain, since `compress` has no stream to act on there. A
chunked body is compared without its chunk boundaries, so how a server splits a stream into
chunks is not held here.

Each file holds a header comment, then the request line and headers as sent, the request body
if there was one, a blank line, the status line, the response headers in the order they came,
a blank line and the body. Only what is volatile is normalised, and the header comment says so:
CRLF is written as LF; the port is `{port}`; the values of `Date`, `Server` and `Keep-Alive` are
`{date}`, `{server}` and `{keep-alive}`; a CSP nonce is `{nonce}`; a chunked body is shown
without its chunk framing and a gzipped one decompressed, which the `# Body:` line of the file
states; a body that ends without a line feed gets one. `Content-Length`, `Transfer-Encoding`,
`Connection`, `Content-Type`, `Cache-Control`, `X-Accel-Buffering` and every `datastar-*` header
are exact. Tomcat sends no reason phrase, so its status line ends in a space, which the file
keeps: an editor that strips trailing whitespace must leave these files alone.

The cases, the same for every adapter:

- `stream-elements`, `stream-signals`, `stream-scripts`, `stream-sse-fields`: an SSE stream with
  every element patch mode, a selector, the svg namespace, view transitions with and without a
  selector, multi-line elements; signals with and without `onlyIfMissing`, multi-line signals;
  `ExecuteScript` with `autoRemove` and with attributes; an event id, a retry and a comment.
  The heartbeat is off, so the text carries no timing.
- `response-elements`, `response-signals`, `response-script`, `response-no-content`: the non-SSE
  answers with their `datastar-*` headers, and a 204.
- `signals-*`: what a handler reads back, echoed as `{"method":...,"read":...}`: a `GET` whose
  query holds `+` and `%2B`, a `GET` without the parameter, a `DELETE` with signals in the query,
  `POST`, `PUT`, `PATCH` and `QUERY` with a JSON body, an empty `POST`, a form-encoded `POST` (415) and a `POST` over
  `maxSignalsSize`, 256 bytes here (413).
- `gzip-stream`: a stream asked for with `Accept-Encoding: gzip` from an instance with
  `compress = true`; `csp-page`: a page from `CspNoncePlugin` or `CspNonceFilter`;
  `script-aliased`: an `ExecuteScript` under `DatastarAttributes.prefix = "data-star-"`.

The tests are `KtorCioWireTranscriptTest` and `KtorNettyWireTranscriptTest` in `streamlord-ktor`,
which `testOldestKtor` runs on Ktor 3.0.0 as well, and `SpringMvcWireTranscriptTest` and
`SpringWebFluxWireTranscriptTest` in `streamlord-spring`. The request set lives once, in
`conformance/wire/kotlin/WireTranscripts.kt`, which both modules' tests compile. After an intended
change, re-record and review the diff
with the rest of it:

```sh
./gradlew :streamlord-ktor:updateTranscripts :streamlord-spring:updateTranscripts
```
