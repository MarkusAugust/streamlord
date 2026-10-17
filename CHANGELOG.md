# Changelog

All notable changes to Streamlord are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `heartbeat` on `respondDatastar` and `datastarStream`, in both variants, and on
  `Streamlord.stream`: a keep-alive interval for one stream, or `null` for none, in place of the
  configured one. It defaults to `Streamlord.heartbeat`, so nothing changes until it is passed.
  One that is not positive throws where it is passed, before the response starts. The functions
  keep their JVM names, and the 0.12 signatures remain, hidden, for code compiled against them.
- `timeout` on `datastarStream`, in both variants: how long Spring lets this stream run, in place
  of `spring.mvc.async.request-timeout` and the container's 30 seconds behind it.
  `Duration.INFINITE` lifts the limit; `null`, the default, leaves Spring's setting alone, and one
  that is not positive throws. It applies to the async cycle that runs the stream, also from a
  `suspend` controller that passes the request, and finds the request through the parameter or
  Spring's `RequestContextHolder`; outside a request that Spring MVC is handling it throws rather
  than doing nothing.
- `heartbeatContext` on `Streamlord.stream`: the context the heartbeat runs in, added to the
  caller's, for an adapter whose block may block its thread. One that carries a `Job` throws.
- `executor` on `datastarStream`, in both variants: the executor that runs this stream, in place
  of Spring MVC's, whose Boot default has eight threads and an unbounded queue. Set on the async
  cycle that runs the stream, also from a `suspend` controller that passes the request; outside
  a request that Spring MVC is handling it throws.
- `SseParser.finish()` in `streamlord-analysis`: the comments that end a stream after its last
  message, handed back when it ends. Both Stream Inspectors call it, so a closing comment shows.
- `Requests.ACCEPT`, `readsAsStream`, `unreadBody`, `NON_STREAM_LIMIT` and `NON_STREAM_CUT` in
  `streamlord-analysis`, for both Stream Inspectors: the `Accept` Datastar's client sends, how it
  decides that a response is a stream, and how much of any other response the inspectors read.

- `Casing.signalName`, the check a name that declares a signal passes: what `Casing.validate`
  admits, and only ASCII letters, digits and `_` in parts joined by `.` or `-`.
- `mergePatchAsClient` in `streamlord-core`: the signal store as Datastar's client reads it after
  a patch, which RFC 7386 is not: `onlyIfMissing` leaf by leaf, an object onto an array by index,
  an array or a string at the top level as keys, a `length` key resizing an array, and a `null`
  inside a stored array read as `""`.
  `DatastarEvents.signals` and both Stream Inspectors fold with it. Each rule was measured against
  the client.
- In `streamlord-analysis`, for both editors: `Requests.requestHeaders`, the headers the client
  sends with the user's replacing its own; `Requests.requestsPathProblem`, what is wrong with a
  configured requests-file path; `RequestsFile.error`, why a requests file could not be read; and
  `AttributeSpec.returnsValue`, from the catalog, for the attributes whose value Datastar reads.
- `JsonWriter.writeExpression`: JSON for a place where Datastar's client reads it as an
  expression, such as `data-signals`, with every `\` written as `\u005c`.

- `SignalNames` in `streamlord-core`: the keys and names Datastar's client cannot hold, with the
  reason (`keyProblem`, `pathProblem`), and `nest`, which nests dotted names the way
  `data-signals:a.b` does and hands a clash to a function of yours.
- `Casing.heldKey` in `streamlord-html`: refuses an attribute key whose signal, as Datastar's
  client reads it after lowercasing, cutting at `__` and applying `__case`, it cannot hold.

### Changed

- A dotted name in the pairs of `patchSignals`, `removeSignals` and `dataSignals` now nests, as
  `data-signals:form.email` does: `patchSignals("form.email" to "x")` patches `$form.email`. It
  used to send the key `"form.email"`, which the client stores as one key that `$form.email`
  does not reach. `removeSignals("form.email")` sends `{"form": {"email": null}}`, and where
  `form` is missing or holds a plain value, the client makes it an empty object; a name under
  another one being removed is left out.
- `patchSignals` throws `DatastarEventValidationException` on a name given twice, or given a
  value and signals under it, where the last value used to win, as `dataSignals` already did. Two
  maps under one name merge.
- A name in the pairs of `patchSignals` and `removeSignals`, and every name the HTML DSL writes,
  may not have an empty part or a part that is a property of `Object.prototype`, as written or
  camel-cased: `patchSignals("constructor" to 1)` and `dataSignals("to-string.name", "1")` throw.
- `dataSignals` refuses `__proto__` with `InvalidSignalNameException`, as it refuses any other
  name, rather than `IllegalArgumentException`.
- `datastarEvents` and `LiveDatastarStream` in `streamlord-test` throw
  `DatastarEventValidationException` on a stream whose signals hold a key the client cannot hold,
  as they do on signals that are not JSON, and `assertSignal` no longer looks for a key holding
  a dot, since none can be read. A dotted key is refused inside an array too, where the client
  would keep it, so that one rule holds everywhere.
- `respondDatastar`, its `Flow` variant and `respondElements`, `respondSignals` and `respondScript`
  on Ktor no longer take a `status`, as on Spring: Datastar's client reads neither a stream nor a
  body with any status but 200. Source that passed one, also positionally as the first argument
  of `respondDatastar`, no longer compiles; code compiled against 0.12 still links.
- `datastarElements`, `datastarSignals`, `datastarScript` and `toResponseEntity` no longer take a
  `status`. Datastar's client reads the body of no status but 200, so any other made the response
  do nothing. Source that passed one no longer compiles; code compiled against 0.12 still links.
- Some events that could be built before now throw `DatastarEventValidationException`, and so does
  an `SseFrame` built by hand: a `retry` that is not a whole number of milliseconds from 1 to
  `Int.MAX_VALUE`, and an `eventId` that is not printable ASCII or has a space at either end. The
  line carries whole milliseconds, so a fraction was cut off, and under one it read `retry: 0`, a
  reconnect with no wait whose backoff never grows; above the limit the browser's timer overflows.
  The id goes back as the `last-event-id` header, and only printable ASCII survives that: `fetch`
  cannot send above U+00FF, Tomcat and Netty answer a control character with 400, Ktor's CIO turns
  Latin-1 into U+FFFD, and edge spaces are stripped.
- `SseDecoder`, `SseReader` and with them `streamlord-test` (`datastarEvents`, `DatastarEvents`,
  `LiveDatastarStream`) read a stream as Datastar's client does rather than as the browser's
  `EventSource`, and `SseDecoder` now reads through `SseReader`, so a text and a stream still
  arriving are read by one parser. A line without a colon is skipped, so a bare `event` or `id`
  keeps the value before it, and a byte order mark before a field or a value is dropped. An `id`
  is kept in `messages()` as written; decoding fails on one that would not come back as written
  as `last-event-id`, by the rule events are built with: printable ASCII, no space at either
  end. A `retry` that is not a whole number of milliseconds from 1 to `Int.MAX_VALUE` fails too.
  Both
  are checked on every message, Datastar's or not, and on an unfinished one at the end, since
  the client takes them per line; `SseReader.finish()` and `LiveDatastarStream` check that last
  one as well. A data line with no space after its key fails: the client would read the key as
  the line minus its last character. `onlyIfMissing` and `useViewTransition` are true with spaces
  around `true`, as the client trims them.
- On Spring WebMVC the heartbeat runs on `Dispatchers.IO`, beside the block, so a block that
  blocks its thread no longer holds it back. An authorisation's `allows()` and `onRefused`, when
  the heartbeat asks, run there without the request's thread-locals.
- The heartbeat is on by default: `Streamlord.DEFAULT_HEARTBEAT`, 30 seconds, for `Streamlord()`,
  `Streamlord.Default` and the Ktor plugin, and for code compiled against 0.11 or 0.12 that
  relies on the default. Every stream Streamlord opens on Ktor or Spring WebMVC that is silent for
  that long now carries a `: keep-alive` comment, which `DatastarEvents.messages` counts; a
  reader who has gone is found by the comment's failed write, and an idle stream with an
  authorisation is asked again on its schedule. `heartbeat = null` turns it off, on the instance
  or for one stream.
- A stream silent from the start gets its first comment after five seconds, or the interval if
  it is shorter. Netty sends no headers before the first byte of the body and closed such a
  stream after ten seconds. The comment commits the response, so a failure in the block after it
  cuts the stream off rather than answering with a status. On Spring WebMVC, with
  `StreamlordExceptionHandler`, a failure before it still answers with its status; on Ktor the
  status is fixed when the stream starts, as before.
- A test that calls `Streamlord.stream` directly under `runTest`, with a block that waits on a
  real thread, now gets comment after comment while virtual time skips ahead. Give such a test
  `Streamlord(heartbeat = null)`. Ktor's `testApplication` and Spring's `datastarStream` run in
  real time and are not affected.
- A lambda passed inside the parentheses as the last positional argument of `datastarStream` or
  `respondDatastar`, `datastarStream(streamlord, request, authorisation, { … })` or with a
  function reference, now lands on `timeout` or `heartbeat` and no longer compiles. Pass the block
  after the parentheses, as almost all code does, or as `block = …`. Java source written against
  0.12 no longer compiles: javac does not see the hidden signatures, and the new ones take a
  `kotlin.time.Duration`, which Java can only pass as `null`: no timeout change, and for
  `heartbeat` no heartbeat at all, the first comment included. Binaries compiled against 0.12 are
  not affected.
- `dataSignals` in its pairs form reads its keys the way the keyed form reads its name, nested
  maps included: `dataSignals("foo-bar" to 7, "user" to mapOf("first-name" to ""))` declares
  `$fooBar` and `$user.firstName`, the signals `signal("foo-bar")` and `signal("user.first-name")`
  refer to. It declared `foo-bar`, which the helpers could not reach. Two keys that become one
  name throw `InvalidSignalNameException`. The object form and a patch from the server keep their
  keys, and `signal("['foo-bar']")` reaches one.
- A name that declares a signal, in `dataSignals`, `dataComputed`, `dataBind`, `dataRef`,
  `dataIndicator` and `dataMatchMedia`, must be ASCII letters, digits and `_` in parts joined by
  `.` or `-`, since that is all Datastar's client reads after a `$`; `über` or `a:b` declared a
  signal no expression reaches. `signal` and the other expression helpers refuse a name that
  starts with a character the client does not read after `$`. A `/` in any key throws
  `InvalidSignalNameException`, where it reached kotlinx.html and threw its own exception.
- A duration in a modifier or a retry option that the browser's timers cannot honour throws:
  a negative one, which fires at once, and one over `Int.MAX_VALUE` milliseconds, about 24.8
  days, which wraps into a negative one. A flag whose duration is not set throws instead of being
  dropped: `debounceLeading`, `debounceNoTrailing`, `throttleNoLeading`, `throttleTrailing` and
  `leading` on `dataOnInterval`. So do an interval of zero, `half`, `full` and `threshold`
  together on `dataOnIntersect`, of which the client reads one, and a negative `retryMaxCount` or
  `retryScaler`.
- `SignalFilter.regexLiteral` writes the quoting of `Regex.escape`, `\Q...\E`, as escaped text,
  where JavaScript matched the letters `Q` and `E`, writes an octal escape as `\xHH`, and groups a
  character outside the Basic Multilingual Plane so a quantifier takes all of it. It refuses what
  JavaScript reads differently or refuses: possessive and stacked quantifiers, atomic groups,
  `\A`, `\z`, `\Z`, `\G`, `\p{...}`, `\x{...}`, `\b{...}`, `\c` before anything but a capital,
  a back reference to no group or followed by a digit, an escaped surrogate, a character outside
  the Basic Multilingual Plane in a class, the other Java-only escapes, and nested or intersected
  classes. `\Q(?i)\E`, the escape of a text with an inline flag in it, is no longer refused.
  Each was checked against Node.
- `JacksonSignalsCodec` and `Jackson2SignalsCodec` hold three rules whatever the mapper says: a
  `null` for a primitive is refused, also for one with a default and under the Kotlin module's
  `NullIsSameAsDefault`, where Spring Boot's mapper read `{"count":null}` as `0`; a missing Kotlin
  parameter that has no default and cannot be `null` is refused, while a Java creator keeps the
  mapper's rule; and nothing may follow the JSON value. A Kotlin class or interface needs
  `jackson-module-kotlin` on the mapper, and decoding one without it throws
  `SignalsCodecException`, also a class Jackson could build without the module, such as one with
  `var` properties.

- The editors judge an expression as Datastar compiles it. Every `$name.path` outside quotes is
  written as `$['name']['path']` first, so a signal as a parameter, `$items.map($i => $i.name)`, a
  declared name or a property key is the syntax error it is in the browser. The value of
  `data-text`, `data-show`, `data-class`, `data-style`, `data-attr`, `data-signals`,
  `data-computed` and `data-on-signal-patch` is then split into statements on `;` outside quotes
  and regex literals, and the last is wrapped in `return (...)`, so a `;` inside a block or a `//`
  comment at the end is an error too; the others are the body of a function that is not async, so
  `await` at the top level is an error. `&dollar;`, the other named references for ASCII
  punctuation, `&amp` without its semicolon and `&#36` without its semicolon are read as the
  browser reads them.
- The editors flag the pre-1.0 syntax, `data-on-click`, `data-on-load`, `data-bind-value`, which
  1.0 ignores, with the 1.0 form as the fix, and `data-on:click.prevent`, which listens to an
  event named `click.prevent`. A capital in `data-attr:viewBox` is fixed to the object form, which
  keeps the name, where the fix wrote `view-box`; for `data-style:--brandColor` no form keeps it,
  and the message says so.
- The editors read `$id` inside a JavaScript string of an expression, `@post('/x/$id')`, as the
  server value it is, a hint rather than an error with fixes that broke the URL; a `$my-value`
  the page declares with `__case.kebab` is no longer a kebab-case warning; the object form of
  `data-computed` declares its keys; and `dataSignals("foo-bar", "1")`, `dataBind("foo-bar")` and
  the other name-taking helpers declare `$fooBar`, as they write it. `attributes["data-on:click"]
  = "..."`, kotlinx.html's own form, is judged as the attribute it writes, interpolation trap
  included.
- Both Stream Inspectors keep the signal store as the client keeps it, see `mergePatchAsClient`,
  and read `onlyIfMissing` with its spaces trimmed, as the client does. The user's headers replace
  the client's own, `Content-Type` included, where IntelliJ added a second one and VS Code
  overrode the user's. Copy as curl writes the body with `--data-raw`, since with `--data` curl
  read signals that start with `@` from the file they name. Signals written as an object in
  `inspector.json` are sent as written, where VS Code rounded a number past 2^53 and the two
  editors spelled them differently.
- Both Stream Inspectors refuse to save over a requests file that is not JSON and say why, where
  a save wrote an empty list over it; a byte order mark in `inspector.json` or `env.json` no
  longer hides what they hold, in either editor; and a requests-file path that leaves the
  workspace, which a checked-in setting may carry, falls back to the default and a save says so.
- The IntelliJ inspections take the severity set for them in the profile; by default each issue
  keeps the one the analysis gives it, since a family mixes a syntax error with a hint. The
  inspector shows event names, content types and saved names as text, where Swing rendered one
  that started with `<html>`, images and all. The `data-init="@get(...)"` live template is named
  `data-init-get`, so it no longer hides behind the generated `data-init`.
- In VS Code, "Open Stream Inspector with route" is gone from the command palette, where it threw
  without a route; "Apply recommended colors" reads the user settings alone, so a workspace's
  token rules are no longer copied into them.

- The build and the workflows are pinned: every GitHub Action by commit, with its version in a
  comment Dependabot keeps in step; the Gradle wrapper by `distributionSha256Sum`; the demo's base
  image by digest; Bun and the Railway CLI by version. Checkouts no longer persist the token in
  `.git/config`, the Railway token reaches only the two steps that deploy, and the three publish
  jobs run in a `release` environment that takes release tags alone. The IntelliJ publish job
  stops when the signing certificate secrets are missing, where it published an unsigned plugin
  under a workflow that looked as if it signed it. The javadoc jars carry `META-INF/LICENSE`, as
  the licence page promises and the other jars do, and every published main jar names an
  `Automatic-Module-Name`.
- `checkDocSamples` fails in CI on a page whose coordinates or wire transcripts it would have
  rewritten, so a release commit that forgets them goes red on the tag; locally it still rewrites
  them. The test-only Tomcat is 11.0.27, past three advisories on 11.0.24. The native image
  passes `-H:+UnlockExperimentalVMOptions` ahead of the experimental options GraalVM will refuse
  without it, and two constructs Gradle 10 removes are gone from the build scripts.

- `streamlord-analysis`, which only the two editors consume, changed the signatures of
  `ExpressionValidator.validate`, `MarkupValidator.validateAttributes`, `Requests.parseRequestsFile`,
  `MarkupOptions`, `RequestsFile` and `AttributeSpec` with new parameters that have defaults, so
  Kotlin source still compiles against them and code compiled against 0.12 does not link. The
  `signalsTooLarge` and `signalsUnreadable` handlers of `StreamlordExceptionHandler` take the
  exception; the 0.12 signatures remain, hidden.

### Fixed

- A signal patch could carry a key Datastar's client cannot hold, from pairs, a codec or a JSON
  string, at any depth. `__proto__` with an object under it made its merge throw halfway through
  a batch it never closed, so no later signal reached the page. An object under a property of
  `Object.prototype` was written into the built-in one before every request:
  `{"constructor": {"prototype": {"x": 1}}}` set `x` on `Object.prototype` for every object on
  the page, and `{"toString": {"name": 1}}` made every later request fail. A dotted key was
  stored as one key that `$a.b` does not reach, and dropped from the client's requests.
  `PatchSignals` and `SignalsResponse` now throw `DatastarEventValidationException` on such a
  key; a plain value under `constructor` or `toString` goes through.
- `data-signals:constructor.prototype.x`, `data-signals:to-string.name` and the same names in
  `dataBind`, `dataRef`, `dataIndicator`, `dataComputed` and Pro `dataMatchMedia` set a property
  on `Object.prototype` or broke every later request in the client. Such a name is now refused,
  also where the client reads it differently from how it is written: `"Constructor"` with
  `Case.KEBAB` is read as `constructor`, `"_-proto-_"` with `Case.SNAKE` as `__proto__`, and
  `"constructor_"` with `ifMissing` as `constructor`, since the client cuts the key at the first
  `__`.
- A value ending in `\` emptied the attribute it was written into. Datastar's client cuts an
  expression into statements at each `;` outside a string and reads a quote after a `\` as
  escaped, so `dataSignals("path" to "C:\\", "sep" to ";")` declared no signal at all, and
  `js("C:\\")` followed by a `;` in `data-text` showed nothing. `dataSignals` now writes `\` as
  `\u005c`, through the new `JsonWriter.writeExpression`, and the HTML helpers' literals write it
  as `\x5c`.
- The literals the HTML helpers write (`js()`, `set()`, `setAll()`, the fetch options and the Pro
  actions) left `"` and `&` as they were. Pasted between the double quotes of an attribute in a
  string or a template, as the HTML DSL page says they can be, a `"` in a value ended the
  attribute, which let a value such as `"><img src=x onerror=…>` run script, and `&lt;` reached
  the client as `<`. Quotes, `&` and `\` are now written as `\xHH`, so `'it\'s'` is now
  `'it\x27s'`.
- A `'` in a regex filter opened what Datastar's client took for a string, so a signal after the
  filter in the same expression was left as a global variable: `@setAll(true, {include: /it's/});
  $msg = 'done'` set `window.$msg`. A `"` broke the attribute in a string, and the client rewrote
  `$name` and `@name(` in one as a signal and an action, so `/^[_$a-z]+$/` matched no signal
  name. Quotes,
  backticks, `&`, `\`, `$` and `@` in a regex filter are now written as `\xHH`, and an anchoring
  `$` before a word character as `$(?:)`.
- `JsLiteral` wrote a map key such as `$id` bare, which the client rewrote as a signal, and the
  whole expression failed to compile. Such a key is now quoted.
- Documentation corrected where the audit found it wrong: the README's MIT condition names the
  licence text as well as the notice and links one documentation domain; the security page's
  ordered-delivery prose names the raid the transcript shows and says the JSON parser is run
  against a fixed seed, not fuzzed afresh; the CSP note no longer says Spring Security's writer
  can agree with the nonce; the WebFlux page says Spring writes no space after any colon; the
  roadmap's release counts are gone; and the editor READMEs link a page that exists on `master`.
  The site's link check now covers same-page anchors.
- `Jackson2SignalsCodec.default()`, and any Jackson 2 mapper such as Spring Boot's, read a missing
  or `null` primitive as `0`: `{}` into `data class Delete(val id: Long)` reached the handler as
  `Delete(id=0)`. It is refused.
- `JacksonSignalsCodec` over a mapper without `jackson-module-kotlin` built a Kotlin class from
  its defaults and dropped what the browser sent: `{"page":3}` into `page: Int = 1` gave `1`.
- `SignalFilter.regexLiteral` escapes U+2028 and U+2029, which ended the literal and made the
  client throw a SyntaxError.
- The Pro attributes wrote a duration under a millisecond as `0ms`, where the free ones refuse
  it; they follow the free attributes' rules now.
- `dataStyle("--brandColor", ...)` wrote `--brand-color`, another custom property. A custom
  property with capitals is refused, since Datastar cannot set one in either form: the browser
  lowercases the key, and the object form turns each capital into a hyphen.
- `collectSignals` and `collectDeclaredSignals` in `streamlord-analysis`, and the VS Code
  extension, read the top-level key of `dataSignals("foo-bar" to 1)` as declaring `$fooBar`, as
  the DSL now writes it. A key with a hyphen in the pairs form was not collected at all. Keys of a
  nested map are still offered as names of their own, as before.
- The optimistic-UI example on the components page threw when it rendered: it set an attribute
  on the `li` after its button. The documentation's `sample=html` examples now run as part of the
  build as well as compile.
- `SseFrame(id = "")` builds again: an empty id is valid SSE and drops the client's
  `last-event-id`, and 0.12 took it. An event still refuses one.
- `SseDecoder`, and with it `datastarEvents`, fails on a data line whose key `Object.prototype`
  has, `constructor` or `__proto__` among them, which makes Datastar's client throw and reconnect
  instead of patching; and it reads an empty `selector` as none, as the client does.
- An inner patch of nothing is allowed, the one way to empty a container, and an
  `ElementsResponse` for an inner or remove patch needs no body.
- A non-SSE response refuses a selector that is not printable ASCII, which reached the client
  garbled or lost as a header, and writes its script attributes with `\u` escapes, which
  `JSON.parse` restores.
- `JsonWriter` escapes a surrogate without its other half, which arrived as a replacement
  character, and `JsonNumber("-0")` keeps its sign.
- `ExpressionGuard` catches what the Dollar trap page said it does: a residue of only `;`, a
  statement that starts with an operator after a `;`, keyword operators with a side missing
  (`in`, `typeof`, `void` and the like), a leading binary `+` or `-`, and an empty `${}`. It reads
  strings, regex literals and comments left to right, so `/'/.test($s)` no longer fails.
- `ElementsGuard` decodes the references the browser decodes: the named ones for the ASCII an
  expression is made of, `&plus;` among them, and numeric ones without their semicolon.
- `redirect` refuses a URL with a scheme that does more than navigate, such as `javascript:`,
  which the browser would run on the page; `ExecuteScript` refuses an effect attribute beside
  `autoRemove`, which the browser dropped along with the removal; and its KDoc and the CSP nonce's
  say what the client does with a nonce and which attribute the aliased bundle reads.
- A `CancellationException` thrown by an authorisation's `allows()`, such as a `withTimeout` around
  a database call, ended only the coroutine that asked. When the heartbeat asked, that was the
  heartbeat, which stopped without a word, and an idle stream was never asked again nor found a
  reader who left. It now ends the stream as an `IllegalStateException`, on both paths.
- On Ktor, a block that throws has its frames delivered and its stream cut. With
  `heartbeat = null`, CIO lost the last frame in about two failures of five and sent no bytes at
  all in most failures before a first write, some of which then hung open; Netty ended half of
  them as if they had finished, which the client does not retry. Ktor gives a writer no way to wait
  for the engine, so a failure now waits a moment, at most a second for a stalled reader, before it
  is thrown on; over 200 failures of each kind on each engine, none lost a frame or ended cleanly.
  Ktor's Tomcat and Jetty engines ended a failed body with its last chunk, as if it had finished,
  gzipped or not, so the client did not reconnect and the page stopped updating without a word.
  Streamlord now cuts the connection there too, on Tomcat 10.1 and 11 and Jetty 11 and 12, over
  HTTP/1.1 and HTTP/2, where only the stream is reset. It does so by reflection, with no new
  dependency, and on Tomcat only once Ktor has completed the request, and not on a connector set
  to keep its facades, where a cut reached other requests. On an engine Streamlord does not know,
  the stream ends as the engine ends it.
- `respondDatastar` on a call already answered throws `IllegalStateException`. It did nothing on
  CIO and threw Netty's own exception from the header loop.
- Neither Ktor nor Spring WebMVC sends `Connection: keep-alive` to a request that asked to close.
- `streamlord-test` folds a signal patch onto an array as Datastar's client does: an index past
  the end extends it, and `null` leaves a hole, read as `null`. It dropped both.
- `LiveDatastarStream` sends the client's `Accept`, now `DatastarProtocol.ACCEPT`, and words a
  frame it fails on as one Streamlord's rules refuse: the client takes `retry: 0` and `1e3`.
- The pages give the measured disconnect figures (four to five heartbeats on CIO, two on Netty),
  say which engine sends its headers when, and what Netty does with `heartbeat = null`; and the core, `streamlord-test` and the Ktor tests also run on the newest
  coroutines release, and `HttpRequestLifecycle` is tested on Netty.
- On Spring WebMVC, a failure inside a stream that has started is cut off, as the documentation
  said, also when `StreamlordExceptionHandler` handles it: the handler throws the exception on
  when the response is committed. It used to answer, which ended the chunked body as if the stream
  had finished, so Datastar's client neither retried nor reported it, and a body an advice
  returned was appended to the event stream.
- `datastarStream` sets `Content-Encoding: gzip` and `Connection: keep-alive`, and starts the gzip
  member, with the stream's first write, so a failure before it, a `500` or a mapped `413` or
  `503`, is no longer answered as gzip over a plain body.
- A `ShallowEtagHeaderFilter`, or any `ContentCachingResponseWrapper`, no longer holds a stream back
  until it ends: the stream flushes the response underneath it.
- On Jetty, a stream is no longer sent one byte per chunk. Its buffer was set to none, which Jetty
  reads as one byte; it keeps the size it has.
- A stream on a response that something wrote to without ending its line, which lost its first
  event to that line, writes a blank line first. This also comes after a change in this release:
  0.12 failed on such a response, with a 500.
- `datastarStream` on a response whose writer is taken throws `IllegalStateException` where it is
  called, not later on Spring's async thread.
- A `suspend` controller that resumed on the thread of another request, and passed `timeout` or
  `executor` without the request, set them on that other request. It now throws, as it does with
  no request at all.
- `readSignals` and its siblings on a request whose declared body was already read, by a
  `@RequestBody`, a form or multipart filter, or a second read, throw `IllegalStateException`
  rather than read no signals and return the default. `maxSignalsSize = Int.MAX_VALUE` no longer
  fails with an overflow.
- `interpolate` and `ElementsGuard` read markup as the browser's tokenizer does. A value got past
  `interpolate`, and a `$`-trap residue past the guard, where the scanner read the markup
  otherwise: a comment closed by `<!-->` or `--!>`, a bogus comment such as `<!x …>`, a
  non-ASCII letter after `<`, `</titlex>` taken to end a `title`, the raw text elements it did not
  know (`xmp`, `iframe`, `noembed`, `noframes`, `noscript`, `plaintext`), and a `title` or a
  `textarea` inside `<svg>`, which holds elements there. `interpolate` reads the markup both as
  HTML and as the inside of an `<svg>` or `<math>` and holds a value to both; the guard reads it as
  svg where the markup opens one or the patch carries an SVG or MathML namespace.
- `interpolate` judges a URL attribute whole, every value in place, so `href="%s%s"` with `"java"`
  and `"script:alert(1)"` throws, as does a hole in `href="javascript:…(%s)"`, which used to be
  escaped and admitted. `values`, `to` and `from` on SVG's animation elements are URL attributes,
  and custom prefixes are tried longest first, as the guard already did.
- `PatchSignals` and `SignalsResponse` refuse signals that are not one JSON object, and `JsonNumber`
  text that is not a JSON number, with `DatastarEventValidationException` and
  `IllegalArgumentException`. Datastar's client runs signals that `JSON.parse` refuses as
  JavaScript, and `JsonWriter` wrote a `JsonNumber` as it was given. An array or a string, which
  arrived as an object keyed `0`, `1` and on, is refused too. `SseDecoder`, and with it `datastarEvents` and
  `LiveDatastarStream`, fails on such signals where it reads them, since the client would run
  them.
- A script body escapes `</body>`, `</head>` and `</html>`, which made Datastar's client read the
  patch as a whole document and drop the script or append a second `html` element, and a
  `<script` after `<!--`, which kept the element open past its end tag. `JsonWriter` and
  `JsLiteral` escape `<!` in strings for the same reason, so `redirect("/q?<!--<script>")` runs.
- `JsLiteral` writes a `__proto__` key as a computed key, the only form that is a key in an object
  literal rather than the prototype, and `dataSignals` refuses `__proto__`, which Datastar's
  client never creates.
- The Security page no longer says `data-ignore` contains raw user HTML: the user's `</div>`
  closes the wrapper, and a patched `<script>` runs inside it. User HTML has to be sanitized
  first.
- `streamlord-ktor` runs on every Ktor 3. Ktor's reified helpers inlined a `TypeInfo`
  constructor that only Ktor 3.6.0 has, so on Ktor 3.0 to 3.5 every `respondDatastar`, the plugin
  and `cspNonce` failed with `NoSuchMethodError`. Its tests now also run, compiled against Ktor
  3.0.0, on the adapter as it is published.
- Every module compiles in a project on Kotlin 2.2, which Spring Boot 4.0 manages. They carried
  Kotlin 2.4 metadata, which a Kotlin 2.2 compiler refuses to read. They are built at language and
  API level 2.2 and name the 2.2 standard library in their POMs, and the documentation's examples
  are compiled with Kotlin 2.2.21 on every build.
- `datastarStream` without a request no longer sends `Connection: keep-alive` over HTTP/2, which
  curl and Node refuse; Tomcat passes the header through. It finds the request Spring MVC is
  handling for the response when none is passed, and sets the header only for HTTP/1.1. With no
  request found, outside Spring MVC or in a `suspend` controller after it suspended, it leaves the
  header out; HTTP/1.1 keeps the connection open without it.
- `datastarStream` on a response something has already written to, but not committed, no longer
  fails with a 500: `prepareForSse` asked for no buffer, which Tomcat, Jetty and Undertow refuse
  once anything is written, and Streamlord now does without it, since every event is flushed
  anyway. Such a stream is not gzipped, since the bytes before it would break the gzip member. A
  committed response, whose headers have gone out, fails with a clear `IllegalStateException`
  rather than the container's.
- The catalog notes that the `responseOverrides` fetch option has no effect in Datastar 1.0.4,
  whose client never reads it, as the DSL's KDoc already did.
- The Stream Inspector, in IntelliJ and VS Code, asks and reads as Datastar's client does. It
  sends the client's `Accept` (`text/event-stream, text/html, application/json`), in the request
  and in the curl line, so a server that answers by `Accept` answers the inspector as it answers
  the page. It reads a 200 that is not HTML, JSON or JavaScript as a stream, a missing
  `Content-Type` included, and shows any other response without a stream, any status but 200
  among them, read up to 1 MiB. A stream with any status but 200 is shown at once with its body
  unread, as the client leaves it, rather than waiting on a body that may never end. IntelliJ
  reads every `Content-Type` value, as the client does, where it read the first. The hint under
  an error without a body no longer blames an `Accept` the inspector no longer sends.
- The Stream Inspector, in IntelliJ and VS Code, reads a stream as Datastar's client does. The
  analysis `SseParser` now reads through the core's `SseReader`, and the VS Code parser is ported
  to the same rules, held to one shared corpus of cases. A message carrying only a `retry` is
  shown; a line without a colon is skipped; a byte order mark before a field or a value is
  dropped; a data line without a space is split as the client splits it; a `retry` past a 32-bit
  int is shown as none in both, where VS Code kept the previous one; an explicit `event: message`
  is shown; a message with comments and an `id` or a `retry` is shown as a frame rather than a
  keep-alive; data lines are joined as the client joins them before they are split, so empty
  ones before the first with text are gone; and a long line in small pieces is read in linear
  time, where it was quadratic. The inspector judges nothing, so a `retry` its last line cannot
  give as a whole number that fits an int shows none, where the client may keep an earlier one or
  read `1e3` as 1000.
- `SseReader.finish()` resets the reader before it rejects an unfinished message, so a reader
  that threw reads on cleanly.
- `SseFrame`, a public data class, validates what it is given: a line break in its `event`, `id`
  or a data line, or a NUL in its `id`, could write a line of its own into the stream, an event or
  data the server never meant to send. A NUL in data is still written, since markup can carry one.
  This reverses 0.12.0, where `SseDecoder` dropped an `id` with a NUL in it and read on: the id is
  now kept as written, and decoding fails on it.

## [0.12.0] - 2026-10-11

### Added

- `streamlord-analysis`, for both editors: `indicator-without-action`, a warning on a
  `data-indicator` whose element sends no request, since the indicator only tracks its own
  element's; and quick fixes for two modifiers that parse as something else,
  `__debounce_150ms` to `__debounce.150ms` and `__prevent.stop` to `__prevent__stop`.
  `validateAttributes` takes the tag's text as well, so that a template construct inside the tag
  is seen.
- `sendLatest(states, minInterval, resumeFrom) { render }` on a stream: renders the newest state
  and sends it, at most once per interval, and not at all when nothing changed. Each render's
  fingerprint is its event id, so a reconnect that passes `last-event-id` back as `resumeFrom` is
  not sent what it already shows. `SseEncoder.fingerprint` computes it. Documentation: the Live
  views page covers commands and the view, the element that opens the stream, and
  `requestCancellation`.
- `Streamlord(compress = true)` and `compress` on the Ktor plugin: gzip on Ktor and Spring WebMVC
  streams whose request takes it, flushed event by event so nothing waits, with the window kept
  across events so a repeated render costs little. WebFlux is not covered. With `compress` on,
  every stream varies on `Accept-Encoding`, gzipped or not. `DatastarProtocol.acceptsGzip` reads
  an `Accept-Encoding` header for the adapters, and takes a weight only as RFC 9110 writes it.
- `replaceUrl(url)` and `pushUrl(url)` on a stream: the server puts the URL of the state it
  rendered in the address bar, replacing the current history entry or adding one, without a
  reload. The URL is quoted as `redirect` quotes it; pass a path on the page's own origin.
  Documentation: a Live views page, which covers them.
- `streamlord-test`: `LiveDatastarStream`, a stream held open and read as it arrives, for a test
  that sends a command and waits for the patch it causes on the stream that was already there.
  `awaitPatchElements`, `awaitSignal` and `next` wait on real time, also inside `runTest`, and fail
  with the stream they read. `open(uri)` uses the JDK's HTTP client and `of(reader)` any other.
  Coroutine API, for Kotlin tests.
- `streamlord-core`: `SseReader`, which reads a stream that is still arriving and hands back each
  message as its blank line arrives, and `SseDecoder.event` for one message at a time.
  Documentation: a Live views page, which covers them.
- Documentation: a Components and plain JavaScript page (attributes down and events up for
  custom elements, window events from plain scripts, `data-ignore-morph` on both sides of a
  morph, optimistic state rolled back on a failed fetch), and the signals page covers signals that
  stay in the browser, `filterSignals` and `payloadExpr`.
- `Streamlord(heartbeat = ...)` and `heartbeat` on the Ktor plugin: a `: keep-alive` comment on
  every Ktor and Spring WebMVC stream that has written nothing for that long, so an idle proxy
  keeps the connection open. With a `StreamAuthorisation`, an idle stream is asked again on the
  heartbeat's schedule. WebFlux's `asServerSentEvents` is not covered. Off by default.
- `streamlord-analysis`, for both editors: `signalReferencesInKotlin` and `signalReferencesInHtml`, every `$name` a
  Datastar expression reads, with source offsets; `collectSignalDefinitions`, the signals a file
  defines without the ones it only reads, plus every `$name = ...` an expression assigns; and
  `unknownSignalIssue`, the `unknown-signal` warning for a read no definition covers, with the
  nearest defined name as the fix. `isKnownSignal` counts `$user.name` as known when `user` is
  defined, and `$form` when `form.email` is.
- `streamlord-analysis`: `ServerLog`, which reads where a server says it started from its log
  (Spring Boot's Tomcat, Jetty, Undertow and Netty lines in 3.5 and 4.0, Ktor's "Responding at"),
  context path included, and which lines are warnings or errors; `RunningServer`.
  `mergeVariables` takes a running server as `baseUrl` when the env file sets none, as the new
  `VariableSource.RUNNING` with the name it was started under; `baseUrlSuggestion` and
  `withBaseUrl` offer and write its URL when the file's differs.
- `streamlord-spring`: `StreamlordExceptionHandler`, a `@RestControllerAdvice` that answers
  `SignalsTooLargeException` with `413` and `JsonParseException` and `SignalsDecodeException` with
  `400`, instead of the `500` they are unmapped. It is not registered for you:
  `@Import(StreamlordExceptionHandler::class)`. It answers with no body, has the lowest
  precedence, and works for WebMVC and WebFlux annotated controllers.
- `streamlord-analysis`: `Issue.position(source)` gives the line and column an issue starts at,
  and `Issue.format(file, source)` writes it as `file:line:column: severity: message [code]`, the
  form IDEs and CI make clickable. `sourcePosition` and `SourcePosition` do the same for any
  offset.

### Changed

- A codec that cannot read a request's signals throws `SignalsDecodeException`, a new subclass of
  `SignalsCodecException`, so a `catch` of the old type still catches it. The kotlinx and Jackson
  codecs do, and so does `readSignals` for a failure it wraps; the built-in reader still throws
  `JsonParseException`. A `SignalsCodecException` of any other kind, a value JSON cannot hold on
  the way out or a type no codec is set up for, is the server's fault: map
  `SignalsDecodeException` to `400` and leave the rest a `500`.
- `StreamAuthorisation.onRefused` runs to its end even while the handler is being cancelled, so
  a frame under way is finished rather than cut, and is given
  `StreamAuthorisation.LAST_WORDS_LIMIT`, five seconds, and no longer; so is the end of a gzipped
  Ktor stream after it. A write that blocks its thread, as a servlet's does, cannot be cut.
- `SseDecoder` ignores an `id:` with a NUL in it, as the SSE spec and the browser do.
- `streamlord-analysis`, for both editors: signal collection, for completion, `signalDrift` and
  the new check alike, honours `__case` on a keyed attribute (`data-signals:my-value__case.snake`
  is `my_value`, `__case.pascal` gives `MyValue`) and reads the keys of JSON text handed to
  `patchSignals`, as in `patchSignals("""{"count": 1}""")`. `keyName(key, case)` names the
  signal a key declares.
- `streamlord-analysis`, for both editors: `Route` carries the `query` parameters and `headers`
  its handler reads, as `RouteParam`s that say whether they are required: Spring's
  `@RequestParam` and `@RequestHeader` from the parameter list, and Ktor's
  `queryParameters["x"]` from the handler's block. `Requests.routeUrl`, `routeHeaders` and
  `routeOptional` turn a route into what "Open in Stream Inspector" writes, a `{{name}}` for each
  parameter.
- `streamlord-analysis`: `.streamlord/env.json` takes a fourth key, `params`, an object of texts.
  Each name in it is a variable for the URL, where its value is encoded, and the headers.
  `resolveRequest` reports a param that is missing or empty, and `withEnvVariables` adds it to
  `params` without touching the rest of the file. `Env` has a `params` field.
- `streamlord-analysis`: an env file that is not valid JSON is reported with where, such as
  `at line 3, column 1` for a comma after the last value.

### Removed

- `streamlord-analysis`: `Requests.pathParams` and `fillPath`, with the dialog they served; the
  inspector writes a `{{name}}` for each path parameter instead, through `Requests.routeUrl`.
- Spring Boot 3 support. The Spring adapter is compiled and tested against Spring Framework 7.0
  and Servlet 6.1 (Boot 4) only, and Streamlord requires kotlinx-coroutines 1.10.2, the version
  Boot 4 pins, instead of 1.8.1. `streamlord-json-jackson2` stays, for applications on Jackson 2.

### Fixed

- `streamlord-analysis`, for both editors: a route whose mapping had no leading slash, such as
  `@RequestMapping("api/hent")` or Ktor's `route("api")`, was found as `api/hent/visning`, and
  the Stream Inspector opened it as `http://localhost:8080api/hent/visning`. Every route path now
  starts with `/`.
- `streamlord-analysis`: `Requests.sendableUrl` refuses a host or a port that `java.net.URI`
  cannot read, such as `localhost:8080api`, `localhost:8080:` or `my_service`, and says which.
  The HTTP client used to refuse them later as "unsupported URI", naming only the URL. A test
  holds the rule to the JDK's own verdict.
- `streamlord-analysis`: `Requests.emptyBodyHint`, the note the Stream Inspector shows under an
  error response that came without a body: the reason is then in the server's log.
- Documentation: Operations said the client drops `last-event-id` when an event carries no id.
  It keeps it, and drops it only on an empty `id:`; the page now says so, with the source line.

## [0.11.1] - 2026-10-05

### Added

- Documentation: the site serves `/llms.txt`, an index of every page with what it is about, and
  `/llms-full.txt`, every page in one file, after llmstxt.org. Both are built from the pages and
  the navigation, and the link check covers their addresses.
- Documentation: the introduction and the README open with the same endpoint written by hand
  against the protocol and with Streamlord, and a screenshot of the editor refusing
  `dataText("$count")`. The introduction shows the three ways to write markup as tabs, and the
  README names them. The testing page says that Ktor 3.6.0's test host hangs on a streamed
  response of 1 MiB or more (KTOR-9968).
- Documentation: the testing page says that on Ktor 3.6.0 an exception thrown inside
  `respondDatastar` does not fail a test, and which assertions still catch it.

## [0.11.0] - 2026-10-04

### Added

- `streamlord-analysis`: `Requests.parseEnv`, which reads `.streamlord/env.json` with its three
  keys and reports anything else, and `mergeVariables`, `describeVariables`, `withEnvVariables`,
  `unreachableHint`, `variableCompletions` and `newRequestLabel`, which the Stream Inspector in
  both editors uses. `resolveRequest` now takes those variables and returns errors and the keys
  that are not set; `parseEnvFile` is gone.

### Fixed

- `streamlord-analysis`, for both editors: a Spring method took the `@RequestMapping` prefix of
  the class before it in the file. A class without `@RequestMapping` now has no prefix, a nested
  class keeps its mapping to itself, and a class mapping is no longer reported as a route when
  another annotation with parentheses in a string, such as `@PreAuthorize("hasRole('ADMIN')")`,
  stands before the class.
- `streamlord-analysis`, for both editors: `patchElements("#rows") { li { } }`, the kotlinx.html
  form, had its selector read as markup and flagged as text outside an element, and a mode
  without a selector went unreported. With a trailing lambda the first string is the selector
  and the second argument the mode.
- `streamlord-analysis`: `Requests.sendableUrl` gives the URL the Stream Inspector sends for the
  URL of a curl line, or the reason it cannot be sent as written.

## [0.10.1] - 2026-10-04

### Fixed

- `streamlord-spring` on Spring Boot: every `readSignals` and `datastarStream` threw
  `NoSuchMethodError: BuildersKt.runBlockingK$default`. Boot pins kotlinx-coroutines in its BOM
  (1.8.1 under Boot 3.5, 1.10.2 under Boot 4.0 and 4.1) and downgrades anything newer, and 0.10.0
  was compiled against 1.11.0. Every module is now compiled against 1.8.1, the oldest pin, and the
  Spring adapter's tests run on 1.8.1, on 1.10.2 with Framework 7, and on the newest release. On
  0.10.0 the way out is `ext["kotlin-coroutines.version"] = "1.11.0"` in your build.
- `streamlord-core`: a `StreamAuthorisation` refusal now holds when the handler does not stop.
  A handler that caught the exception inside a loop went on writing to a reader who had just
  been refused, with no further check. Every write after a refusal is now refused again, only
  `onRefused` may still write, and `Streamlord.stream` reports such a stream as refused.
- Documentation: the Spring WebMVC and WebFlux samples took `Streamlord` as a parameter of the
  handler method. Spring constructs a new default instance for such a parameter instead of
  injecting the bean, so the configured codec and `guardElements` were silently ignored. The
  samples now take the bean through the controller's constructor, and the build refuses a
  sample that does otherwise.
- `streamlord-core`: `interpolate` let a value into attributes the browser acts on. It now
  refuses a value in an event handler (`on*`) or `srcdoc`, takes only a number in `style`, and at
  the start of a URL attribute (`href`, `src`, `action` and the like) takes a relative URL or an
  `http`, `https`, `mailto` or `tel` one. `Position` gains `URL` and `STYLE`.
- `streamlord-html`: `dataNonce` refuses an empty or malformed nonce, as the plugin and the
  filter already did.
- `streamlord-core`: `ElementsGuard` threw `IllegalArgumentException` on a numeric character
  reference that names no character, such as `&#99999999;`. It is left as written.
- `streamlord-json-jackson2`: `Jackson2SignalsCodec.default()` refused a body carrying a signal
  the type does not name, which is every body from a page with more signals than one handler
  reads. It now ignores unknown properties, as Spring's mapper and the other codecs do.
- `streamlord-json-kotlinx`: a body of the wrong shape was reported with a hint to name a
  serializer for a native image. The hint now comes only with a failed serializer lookup.
- `streamlord-spring`: a WebFlux event wrote `retry: 1000`, the protocol default the encoder
  leaves off.
- Documentation, each checked by running it: the `$` trap ships the variable's value, not an
  empty string, and the runtime guard catches only the empty case; `signal()` and the other
  expression helpers refuse blank names and whitespace, not quotes; under Spring Boot's
  dependency-management plugin Boot's Jackson version wins in Gradle too; a body that will not
  parse is a `500` unless you map it; ten modules are published, not nine; and the bytes on the
  wire are identical on Ktor and WebMVC, while WebFlux writes the same frames in Spring's
  spelling.

- Every published jar now carries `META-INF/LICENSE`. The licence page said the licence text
  travels in the jar, and it did not.
- Documentation, from a register of 414 claims checked one by one: Spring Boot's default MVC
  executor runs eight streams at once and queues the rest, where the page said it created a
  thread per stream without limit; the golden files are compared as an equivalence, not byte for
  byte; `sharedLibrary.set(false)` is the default without `java-library`; stale coordinates and
  transcripts are rewritten rather than failing the build; completion collects signals from the
  whole project; and four smaller corrections on the roadmap and changelog pages.

- `streamlord-core`: a number of a million digits in a signal fits inside the default size limit
  and cost over twenty seconds of CPU the first time `int`, `long` or `decimal` read it. The
  parser now refuses a number longer than `JsonParser.MAX_NUMBER_LENGTH`, 1000 characters.
- `streamlord-core`: `JsonWriter` wrote a `BigDecimal` with an enormous exponent in plain
  notation, two billion zeros for `1E+2000000000`. It now keeps scientific notation past a scale
  of 100. A `Float` is written in its own shortest form, a map whose keys are written alike is
  refused, and the writer stops at the depth the parser stops at. The parser no longer takes the
  digits of other scripts in a `\u` escape. `JsonObject` and `JsonArray` equality against a plain
  `Map` or `List` now holds in both directions.
- `streamlord-core`: `SseDecoder` read streams the browser would not act on. A last message with
  no blank line after it is no longer an event, a line may end in a bare CR, a leading byte order
  mark is dropped, mode and namespace are matched as written, a signal patch with no `signals` is
  malformed, an event that is not Datastar's is skipped whatever its fields hold, and an empty
  `id` is no id.
- `streamlord-test`: `assertSignal` honours `onlyIfMissing` leaf by leaf, as the client's merge
  does; compares numbers by value, so `13` is `13.0`; takes `null` to mean the signal is not
  there; finds a key that holds a dot; and fails with an `AssertionError` on signals that are not
  JSON. `assertExactly` compares the frames two events put on the wire, so it takes the
  `ExecuteScript` that was sent and ignores a `retry` of the protocol default.
- `streamlord-html`: `dataBind { prop = "valueAsNumber" }` wrote the property in camelCase into
  the attribute name, which the browser lowercases; it is written `value-as-number`, which the
  client turns back. A name holding `__` is refused, because Datastar reads modifiers from there.
  A positive duration shorter than a millisecond is refused instead of written as zero.
  `SignalFilter.regexLiteral` writes an empty pattern as `/(?:)/`, escapes a line break, and
  refuses inline flags. `JsLiteral` quotes `__proto__`.
- `streamlord-core`: `ExpressionGuard` no longer refuses an expression holding a comment, a regex
  after `=>`, or a number ending in a dot.

- `streamlord-analysis`, and with it both editors, from a review that reproduced each of these:
  ordinary attributes such as `data-id`, `data-test` and `data-href` were flagged as misspelt
  Datastar attributes, with a fix that rewrote them; HTML with optional end tags (`<li>a<li>b`)
  was an error; `__debounce.500`, `$foo.0.name`, HTML entities in an attribute value and an `@`
  inside a string were all refused though Datastar accepts them; a string followed by
  `.trimIndent()` was not checked at a call site, so the CI check for the `$` trap missed it; a
  positional selector in `patchElements` was not seen; a quick fix on an escaped dollar wrote a
  live Kotlin template; U+0130 in the text shifted every later offset; a CRLF split across two
  chunks broke an SSE message in two; and `map.get("key")` was reported as a route. New errors
  say what the Datastar client refuses at run time: `action-space`, `missing-value`,
  `key-and-value` and `missing-key-or-value`.
- `streamlord-analysis`: the JavaScript parser is now a port of acorn, the parser the VS Code
  extension uses. Over a corpus of 682 expressions the two disagreed on the verdict for 67 and
  now disagree on none.
- VS Code extension: a quick fix for an expression inside an HTML attribute edited the start of
  the tag instead of the expression.
- `signalDrift` collects JSON-form declarations, honours `@SerialName`, and counts an accessor
  as a signal read only on a value that came from `readSignals`.

### Removed

- `streamlord-html`: the deprecated aliases `html.ExpressionGuard` and
  `html.InterpolatedExpressionException`, left from 0.1.1. With both `core.domain.*` and `html.*`
  imported they made either name ambiguous. Import them from `core.domain`.

### Added

- `streamlord-spring`: `Flow<DatastarEvent>.asDatastarResponse(streamlord)`, the WebFlux stream
  as a `ResponseEntity` carrying `Cache-Control: no-cache` and `X-Accel-Buffering: no`. A
  controller returning the bare flow from `asServerSentEvents` sent neither, so nginx held the
  events back until the stream ended.

## [0.10.0] - 2026-10-02

### Added

- `streamlord-test`, a new module: `datastarEvents(wire)` reads a Datastar response back into the
  events it carried, and asserts on what a handler meant rather than on the bytes it produced.
  `assertPatchElements`, `assertPatchSignals`, `assertSignal`, `assertNoSignal`, `assertNoEvents`
  and `assertExactly`. Every assertion but the last is satisfied by one matching event and ignores
  the rest, so a test about markup does not go red because a handler started patching a signal.
  `assertSignal` folds the whole stream the way the browser does, so it reports what a signal
  ended up as. It depends on `streamlord-core` and nothing else, and binds no test framework: a
  failure is an `AssertionError`, so kotlin.test, JUnit, Kotest and TestNG all work and none is
  imposed.
- `streamlord-core`: `SseDecoder`, the inverse of `SseEncoder`. `decode(text)` gives the events a
  stream carried and `messages(text)` the raw SSE messages, for a test about a heartbeat comment
  or a resume id. It is held to the same twenty official golden files as the encoder, in both
  directions.
- `streamlord-core`: `mergePatch` moves here from `streamlord-analysis`, into `core.json`. RFC
  7386 is how the protocol defines a signal patch, so anything asking what the browser ends up
  holding has to fold them the same way, and that is no longer the editor tooling's private
  business.

## [0.9.0] - 2026-10-02

### Removed

- `streamlord-core`: `Flow<DatastarEvent>.asSse()`. Of 244 public names across the published
  modules it was the only one with no caller anywhere: not in the tests, the demo, the editors,
  the documentation's examples or the core itself, and no page ever mentioned it. It was one line
  of sugar over `Streamlord.encode(flow)`, which is still there. `explicitApi()` makes you write
  the word `public`; it does not ask whether you meant it.

### Added

- `streamlord-core`: tests for `Streamlord.encode(flow)`, which `asSse` was the only caller of and
  which nothing had exercised: the frames it produces, an empty flow, and that `guardElements`
  stops an eaten expression before it becomes one.

## [0.8.0] - 2026-10-02

### Added

- `streamlord-core`: `StreamAuthorisation`, a check a stream carries for as long as it runs. A
  stream is authorised once and then lives for minutes while the reader logs out or an
  administrator revokes access, and nobody asks again. Pass one where you open the stream and
  Streamlord asks before writing, at the open and then no more often than `every` (five seconds by
  default), so a chatty stream does not cost a database round trip per patch. A refusal runs
  `onRefused` on the still-open stream, so the reader is told rather than dropped, and then ends
  the response rather than letting it fall silent.
- `streamlord-ktor`: `respondDatastar(authorisation = ...)` on both the block and the flow forms.
- `streamlord-spring`: `datastarStream(streamlord, request, authorisation) { }`, likewise.
- `streamlord-core`: `Streamlord.stream(sink, authorisation, block)` runs a stream to the end and
  reports whether it got there. `StreamRefusedException` is what ends a refused one; the adapters
  catch it where they opened the stream.

## [0.7.0] - 2026-10-01

### Changed

- `streamlord-analysis`: `signalDrift` returns a `SignalDriftReport` rather than the issue map
  alone. An empty result means nothing unless the check recognised reads at all, and the report's
  `read` and `declared` sets are how a caller tells a clean project from a check that matched
  nothing. That failure is why this exists: the first version called a 45-file corpus clean
  because its pattern missed every signals class.
- `streamlord-json-kotlinx`: `KotlinxSignalsCodec(strict = ...)` is now `requireNamedSerializers`.
  It never had anything to do with refusing unknown signals, which is the `Json` configuration's
  `ignoreUnknownKeys`, and a flag called `strict` on a signals codec reads as though it did.

### Fixed

- `streamlord-core`: `ExecuteScript`'s documentation offered its attribute map as a place for a
  CSP nonce. Under CSP mode Datastar re-creates every script it patches in and sets the nonce
  itself from the page's, so one passed there is overwritten, and with CSP mode off it does
  nothing. The nonce that matters is the one on the document's `<html>`.

## [0.6.0] - 2026-10-01

### Added

- `streamlord-analysis`: `signalDrift(facts)` reports every signal a handler reads that no markup
  in the project declares, which is the failure that compiles, deploys and then hands the handler
  a default on every request. `collectSignalFacts(src, language)` reads one file for it, and the
  check runs across the set, because the page that declares a signal is almost never the file
  that reads it. Only classes something reads signals into are examined; the drift in the other
  direction is not reported, since a signal nobody reads is harmless.
- `streamlord-analysis`: `collectDeclaredSignals(src, language)`, the declarations alone.
  `collectSignals` is unchanged and is still those plus the properties of `@Serializable` classes.

### Fixed

- `streamlord-analysis`: a `@Serializable` class carrying any modifier before `class` was not
  seen, so `public data class` signal classes, which is what an `explicitApi()` module writes,
  contributed no property names to completion.

## [0.5.0] - 2026-10-01

### Added

- `streamlord-core`: `interpolate(format, vararg values)` writes values into markup at its `%s`
  holes and decides what to do with each by where it landed, not by where it came from. Element
  text and ordinary quoted attributes are escaped. A hole in a `data-*` attribute Datastar reads,
  in a tag name, an attribute name, an unquoted value, or inside a `<script>` or `<style>` is
  refused with `UnsafeInterpolationException`, because escaping cannot make those safe. A number
  or a boolean is admitted into a Datastar attribute, so a server-rendered initial signal needs no
  waiver, and `Trusted(html)` waives both the escaping and the refusals for a fragment you
  rendered yourself.
- `streamlord-core`: `Position`, the five places a value can land, which
  `UnsafeInterpolationException` reports.

### Changed

- `streamlord-core`: `ElementsGuard` now walks markup through the same scanner `interpolate` uses,
  so there is one parser rather than two. Its behaviour is unchanged; it allocates one visitor per
  call where it previously allocated none.

## [0.4.0] - 2026-10-01

### Added

- `streamlord-core`: `CspNonce` in `core.domain` holds the half of Datastar's CSP mode that is the
  same in every framework: `generate()` for a per-response nonce of 16 bytes from `SecureRandom`,
  `starterPolicy(nonce)` for somewhere to start, `checked(nonce)` for a nonce from a generator of
  your own, and the two header names.
- `streamlord-ktor`: `CspNoncePlugin` generates one nonce per call, writes the
  `Content-Security-Policy` header from it, and hands the same value back through
  `call.cspNonce` for `dataNonce` on the `<html>` element. Both ends come from one variable, so
  they cannot drift apart, which until now nothing enforced. It is route-scoped, so an
  application that serves documents from some routes and streams from others installs it where
  the documents are. The policy is configurable, `null` writes no header, and `reportOnly = true`
  writes the report-only header instead.
- `streamlord-spring`: `CspNonceFilter` does the same for WebMVC, with `request.cspNonce` to read
  it back. For WebFlux, `exchange.installCspNonce()` writes both ends from inside a `WebFilter`
  you register yourself; the adapter ships no `WebFilter` of its own, because one would put
  Reactor on its compile classpath.

## [0.3.1] - 2026-09-29

### Added

- `streamlord-json-kotlinx`: `KotlinxSignalsCodec` takes the serializers it should use, by the
  type they handle, and consults them before the reflective lookup. A named serializer is the one
  the compiler plugin generated, resolved at the call site, so it is visible to a GraalVM native
  image, where reading a class to find its serializer cannot work. Passing none keeps the old
  behaviour exactly.
- `streamlord-json-kotlinx`: `strict = true` refuses the reflective lookup for a type that was not
  named, so the omission fails on the JVM with the name of the class instead of inside an image on
  the first request that carries it. It asks only for what an image cannot resolve by itself: your
  own classes, and containers holding them.

### Fixed

- Every module published an empty javadoc jar. The jar now carries the API reference generated
  from the KDoc in the source, so javadoc.io has something to show. Releases up to and including
  0.3.0 are on Maven Central with the empty one and cannot be changed.
- `streamlord-json-kotlinx`: a failure to encode or decode now says when no serializer was named
  for the type, and what to add. It used to say only that the value could not be decoded, which
  sends a reader to look at their JSON rather than at their codec.
- `streamlord-analysis`: a hyphen after a signal is read by Datastar 1.0.4 as part of the name
  (`$foo-bar` is `$['foo-bar']`, `$count-1` is `$['count-1']`; only `$a-$b` is a subtraction).
  The `signal-kebab` issue now says so, covers `$count-1` and the scope-variable cases it
  excused before, and offers the spaced subtraction beside the camelCase signal.

## [0.3.0] - 2026-09-27

### Added

- `streamlord-analysis`: the editor-independent analysis behind the editor tooling, as a Kotlin
  module with no dependency beyond `streamlord-core`. `Analyzer().analyzeKotlin(source)` and
  `analyzeHtml(source)` return every issue with its offset, severity, message, reference link and
  quick fixes: the Kotlin `$` interpolation trap, expression syntax (an in-house JavaScript
  parser, positioned like acorn), unknown and Pro actions, the markup rules of a patch, the
  `data-*` attribute rules, the casing rules and the wire hint. Also there: the Kotlin string
  reader (escapes, templates, multi-dollar literals, an offset map), the call-site scanner, the
  signal and selector collectors, the Ktor and Spring route finder, the Stream Inspector's file
  format and curl export, the SSE parser and the JSON merge patch, the hover documentation and the
  highlighting tokenizer. Tested against the cases of the VS Code extension, so the two editors
  agree.
- `editors/intellij`: the Streamlord plugin for IntelliJ IDEA 2026.1 and newer, Community and
  Ultimate. Inspections with quick fixes, completion, hover documentation, highlighting of the
  Datastar tokens, HTML injection into marker-less HTML strings, live templates, and the Stream
  Inspector as a tool window with saved requests shared with VS Code, variables, gutter icons on
  routes and curl export. Published to the JetBrains Marketplace from CI on an `intellij-v*` tag.

### Fixed

- An expression that opens with `{` is read as the object literal Datastar makes of it
  (`return ({count: 0, name: ''})`), in the VS Code extension and the analysis alike; a script
  parser saw a block and flagged `data-signals="{count: 0, name: ''}"` as a syntax error.

## [0.2.0] - 2026-09-27

### Added

- `streamlord-core`: `ElementsGuard`, the `$` trap hunted in HTML written as a string. It walks
  the tags, finds every `data-*` attribute whose value is an expression and runs it through
  `ExpressionGuard`; the exception names the attribute. `Streamlord(guardElements = true)`
  applies it to every element patch and elements response that leaves through that instance:
  streams, `encode(flow)`, Ktor's `respondDatastar(response)`, Spring's `datastarElements` and
  `asServerSentEvents`. Off by default. The Ktor plugin takes it as
  `install(StreamlordPlugin) { guardElements = true }`. The same guard catches a mistyped
  attribute: a `data-*` name one letter away from a Datastar attribute (`data-onn:click`,
  `data-signal:x`) throws `MistypedAttributeException` naming the attribute it resembles,
  while names further away are taken to be your own and left alone. A bare name without key
  or modifier is only judged when a letter is swapped in a long name (`data-indicater`,
  `data-computer`), so `data-test`, `data-kind`, `data-animated` and `data-effects` pass.
  `<script>` and `<style>` bodies are skipped.
  `ElementsGuard.attributes` lists every Datastar attribute name, bound to the catalog by test.
- `ExpressionGuard` accepts `[]`, `{}`, spread (`...$items`) and leading-dot decimals (`.5`),
  which it refused before as operator-only or dangling, and now catches what an eaten signal
  leaves inside a literal or argument list (`{count: }`, `{n: , m: 1}`, `[, 1]`), a missing
  left operand of `<` and a missing right operand of `+` or `-` (`'Hello, ' + `), which it let
  through before, while `$count ++` with a space, valid JavaScript, is no longer refused.
  `ElementsGuard` decodes the HTML entities kotlinx.html and template engines write
  (`&amp;&amp;`, `&lt;`, `&#x27;`) once, as the browser does, before judging a value; skips
  `<script>`, `<style>`, `<textarea>` and `<title>` bodies; and string and regex literals inside
  an expression (`':)'`, `split(/[,;]/)`, `/(?:a|b)/`) are never read as operators, which
  0.1.1's guard got wrong for `(?` and `(=`. `Streamlord(attributePrefixes = ...)` and
  `ElementsGuard.check(html, prefixes)` take a custom bundle alias, longest matched first.
  `Flow<DatastarEvent>.asSse()` takes the instance too.
- `catalog/datastar-1.0.4.json`: `keyCase` on every keyed attribute (`camel`, `kebab` or
  `raw`), the one place the casing rule is written; the catalog test binds `Casing` to it and
  the extension reads it.
- `@Language("HTML")`, `@Language("JSON")` and `@Language("JavaScript")` on every `elements`,
  `signals` and `script` parameter in core, Ktor and Spring, so IntelliJ injects the right
  language into a literal passed straight in. The annotation's artifact,
  `org.jetbrains:annotations`, is declared `compileOnly`, so nothing new reaches a consumer;
  kotlin-stdlib ships the same artifact anyway.
- README: "Three ways to write markup", covering the DSL, strings (`$$"""..."""`,
  `@Language("HTML")`) and templates as equals, with what each gets from the SDK and the editors.
- `streamlord-html`: `Casing`, the casing rules of Datastar applied so that the name you write
  is the name you get. The browser lowercases attribute names, so the keyed helpers write a
  camelCase name as the kebab-case key Datastar reads back as that name (`dataSignals("fooBar")`
  is `data-signals:foo-bar`, the signal `$fooBar`), adding `__case.camel` for `dataOn` and
  `dataClass` where Datastar's default is kebab, and `__case.pascal` for a leading capital.
  `dataAttr`, `dataStyle`, `dataAnimate` and `dataPersist` write the key in kebab-case. `signal()` and the
  other expression helpers read a kebab-case key the way Datastar names it (`signal("foo-bar")`
  is `$fooBar`). `InvalidSignalNameException` for a blank name or one with whitespace, quotes
  or a character that ends an attribute; `hover:bg-red-500`, `xlink:href` and `--brand` are
  fine as keys, and a reference is JavaScript, so `items[0].name` and `items['sub-total']`
  are fine there. An explicit `case =` is kept, with the key still in kebab-case.

### Changed

- `ExpressionGuard` and `InterpolatedExpressionException` moved from `streamlord-html` to
  `streamlord-core` (`io.github.markusaugust.streamlord.core.domain`), so the string and
  template paths can use them without the DSL. `InterpolatedExpressionException` gained an
  `attribute` property.
- `datastarElements`, `DatastarResponse.toResponseEntity` (Spring), `asServerSentEvents` and
  `DatastarEvent.toServerSentEvent` (Spring WebFlux) take an optional `streamlord` parameter,
  last, for the guard; Spring has no auto-configuration, so pass your bean. `Streamlord` gained the `guardElements`
  constructor parameter, also last. Kotlin callers recompile unchanged; Java callers of the
  full constructor pass one more argument.

### Deprecated

- The `streamlord-html` names `ExpressionGuard` and `InterpolatedExpressionException` remain as
  type aliases of the core ones.

### Fixed

An audit of the DSL against the Datastar 1.0.4 client source and bundles found these; the
protocol layer came through it unchanged.

- `streamlord-html`: `js()`, `set()`, `setAll()`, the fetch options (`headers`, `payload`) and
  the Pro actions (`clipboard()`, `intl()`) write JavaScript literals in single quotes through
  the new `JsLiteral`, as the Datastar documentation does: `@get('/x')`, `$name = 'Gorvek'`,
  `{headers: {'X-Csrf-Token': 't'}}`. The JSON they wrote before survived only the kotlinx.html
  DSL, which escapes double quotes; pasted into a double-quoted attribute in a string or a
  template, it ended the attribute. `dataSignals(vararg)` still writes JSON, which is an
  attribute value only.
- `streamlord-html`: `dataBind`, `dataRef` and `dataIndicator` wrote `__case` on the value form
  (`data-bind__case.kebab="foo"`), where Datastar ignores it, since casing applies to a key
  only. With a `case` they now write the key form (`data-bind:foo__case.kebab`), through
  `Casing.key`; without one they write the value, reading a kebab-case name as Datastar names
  the signal (`dataBind("foo-bar")` is `data-bind="fooBar"`, matching `signal("foo-bar")`).
  The `case` parameter of the object forms `dataSignals("{...}")`, `dataComputed("{...}")`
  and `dataClass("{...}")` was equally inert and is removed; the keys of the object are used
  as written.
- `streamlord-html`: `SignalFilter.regexLiteral` dropped the `RegexOption`s, so
  `Regex("user", IGNORE_CASE)` reached the browser as `/user/`. `IGNORE_CASE`, `MULTILINE`
  and `DOT_MATCHES_ALL` become the `i`, `m` and `s` flags; the options with no JavaScript
  equivalent throw `IllegalArgumentException`. A `/` that the pattern had already escaped is
  no longer escaped twice.
- `streamlord-html`: `setAll()` and `toggleAll()` write `include: /.*/` in front of a filter
  that only excludes, matching the documented signature of the two actions.
- `streamlord-html-pro`: `dataMatchMedia` wrapped the query in bare quotes; a `'` in the query
  broke the expression. It is quoted with `js()`.
- `streamlord-core`: `ExecuteScript`'s `data-effect="el.remove()"` follows the attribute
  prefix, so the aliased bundle gets `data-star-effect` and the script tags are removed as
  intended. The switch is `DatastarAttributes.prefix` in `core.protocol`, and the
  `streamlord-html` object of the same name now sets it.
- `streamlord-core`: `redirect()` navigates from a `setTimeout`, as the official SDKs do, so the
  script element is applied and removed before the page unloads.
- `streamlord-spring`: `datastarStream` and `prepareForSse` take an optional
  `HttpServletRequest`; with it, `Connection: keep-alive` is only set for HTTP/1.1, as the SDK
  specification says and HTTP/2 requires.
- Documentation: the default `filterSignals` exclusion is `/(^|\.)_/`, which also drops nested
  `_` keys such as `user._token`; and Datastar Rocket, the separate beta bundle with a
  web-component API, is stated to be outside what the DSL covers.

## [0.1.1] - 2026-09-26

### Added

- `streamlord-html`: `ExpressionGuard` and `InterpolatedExpressionException`. Every expression
  helper in `streamlord-html` and `streamlord-html-pro` now refuses, at render time, text that
  has the shape a Kotlin string template leaves behind when it interpolates a signal
  (`"$count++"` arriving as `"++"`). The message names the helpers and the `$$"..."` literal.
- README: a section on the `$` trap and the three ways to avoid it.

### Fixed

- CI: the Maven Central publish job no longer fires on `vscode-v*` tags.

## [0.1.0] - 2026-09-26

The first forging.

### Added

- `editors/vscode`: the Streamlord VS Code extension (diagnostics, completions, hover,
  highlighting, Stream Inspector) and `catalog/datastar-1.0.4.json`, the shared source of
  truth verified by `CatalogTest`.

- `streamlord-core`: Datastar 1.0.4 protocol model (`PatchElements`, `PatchSignals`,
  `ExecuteScript`), `SseEncoder` verified against the official SDK golden files, non-SSE
  `DatastarResponse` types, a dependency-free strict JSON parser and writer, the
  `DatastarStream` driving port and the `SseSink`, `SignalsCodec` and `IncomingRequest`
  driven ports, and the `Streamlord` facade with bounded signal reading.
- `streamlord-html`: kotlinx.html DSL for every Datastar attribute, modifier, action and
  expression helper; `patchElements { }` for streams, events and responses.
- `streamlord-ktor`: `respondDatastar`, `respondElements`/`respondSignals`/`respondScript`,
  `readSignals`, the `StreamlordPlugin`, and an in-process run of the official SDK
  conformance server.
- `streamlord-spring`: `datastarStream` over `StreamingResponseBody`, `readSignals` over
  `HttpServletRequest`, `ResponseEntity` helpers, `Flow<DatastarEvent>.asServerSentEvents()`
  for WebFlux, and `ElementPatchModeConverter`.
- `streamlord-json-kotlinx`, `streamlord-json-jackson` (Jackson 3, Spring Boot 4) and
  `streamlord-json-jackson2` (Jackson 2, Spring Boot 3): codec adapters for typed signals.
- `streamlord-html`: `payload` and `responseOverrides` fetch options, `dataOnFetch` with
  `FetchEventType` constants, and `DatastarAttributes.prefix` for the aliased `data-star-*` bundle.
- `streamlord-html-pro`: opt-in helpers for the attribute names and actions of Datastar Pro,
  containing no Pro code. The Spring adapter is compiled against Spring Framework 6.2 and
  tested against 6.2 and 7.0.
