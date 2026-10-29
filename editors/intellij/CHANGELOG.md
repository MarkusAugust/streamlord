# Changelog

All notable changes to the Streamlord plugin for IntelliJ IDEA are recorded here.

## Unreleased

- A key the DSL refuses at runtime, one holding `__` or one ending in `_` before any modifier the call
  adds (its `__case`, `ifMissing`, `session` or the modifiers of its lambda), is the error
  `key-refused`, where the hint used to say what it writes on the wire: `dataClass("isOpen_", …)`,
  `dataSignals("foo_", "1", ifMissing = true)` and `dataSignals("fooBar__x", …)` throw. In IntelliJ the
  check is part of Datastar key casing.
- `patch` in an expression of an attribute that does not hand it over is the warning `scope-name`:
  only `data-on-signal-patch` declares `patch`, so `data-text="patch.a"` throws a ReferenceError.
  In IntelliJ the check is part of Datastar expressions.
- A pair written `"selected" to null` in `dataSignals` is the warning `signal-null`: Datastar's
  client holds no null, so the pair declares nothing, and an expression that reads the signal
  gets `""`. In IntelliJ
  the check is part of Datastar attributes.
- A capital in the key of `dataAttr` or `dataAnimate` is the error `attribute-name-case`, with the
  kebab-case fix, since Streamlord 0.16 refuses it at runtime: the browser lowercases an
  attribute name, so `dataAttr("viewBox", …)` could only set `view-box`. The message names the
  object form, `dataAttr("{viewBox: …}")`, for an attribute that really has capitals. In IntelliJ
  the check is part of Datastar key casing.

## 0.8.0

- A hyphen in a signal name written in a DSL call is the error `signal-name-hyphen`, with the
  camelCase fix, since Streamlord 0.15 refuses such a name at runtime: `dataSignals("foo-bar", …)`,
  `dataBind("form.first-name")`, a pair's key, `removeSignals("a-b")`. In `signal`, `set` and the
  other expression helpers a name is read as Datastar's client reads it: a quoted key is text,
  `signal("form['first-name']")`, while `signal("items[\$i-1]")` reads the signal `i-1` and gets
  the advice to write the subtraction in the expression, without a fix. The second argument of
  `dataSignals` is read as the compiler reads it, so the object form, `dataSignals("{...}", true)`,
  is left alone. In IntelliJ the check is part of Datastar key casing.
- A misspelt name in `signal`, `increment`, `decrement` or `not` gets the same warning and fix as
  `$cuont` in an expression. These calls used to count as declarations.
- `dataSignals(codec.toJsonValue(Form()))` declares the properties of a `@Serializable` class named
  there, for the unknown-signal check. For a class the analysis cannot read, every name
  counts as declared.
- The keys inside a pair's value, `"user" to mapOf("name" to "")`, are no longer counted as
  top-level signals.
- The fix for a hyphenated reference follows the client: for `$a-b-1` the key `a-b-1` declares
  `aB-1`, not `aB1`. The key-case hint for an event or class such as `dataOn("fooBar-baz", …)` says
  that no key gives the name back, where it offered one that did not.
- A signal object under `constructor` or another name every object has is flagged: the client holds
  it, and the next request writes it into the built-in one.
- The highlighter marks `$123` and `$foo-bar` as one signal each, as the client reads them, and a
  `signal()` call that resolves outside Streamlord is left out of the misspelling check.
- Completion, hover and the checks know the object form of Pro's `data-animate`,
  `data-animate="{width: $w + 'px'}"`, and its modifiers `duration`, `delay`, `ease`, `loop` and
  `pingpong`, and the `delay`, `debounce` and `viewtransition` modifiers of `data-on-raf` and
  `data-on-resize`. A `data-animate` without a key is no longer flagged, and its snippet inserts
  the object form; type `data-animate:` for the keyed one.

## 0.7.0

- Expressions are judged as Datastar's client compiles them. Every `$name.path` is rewritten
  first, so a signal used as a parameter, `$items.map($i => $i.name)`, is the syntax error it is
  in the browser; a `;` inside a block or a `//` comment at the end of `data-text`, `data-show`
  and the other attributes whose value is returned is an error; so is `await` at the top level.
  `&dollar;` and the other references for ASCII punctuation are read as the browser reads them.
- Six new checks, each tested against the 1.0.4 client: `literal-rewritten`, a `$name` in a
  regular expression or in a template literal that also holds a `${...}`, which the client
  rewrites; `signal-not-read`, a `'` in a comment, a regular expression or a template that makes
  the client leave a later `$b` alone, and `$år`, since only ASCII follows a `$`; `signal-name`,
  a name the client cannot hold or no `$name` reaches, such as `__proto__`, `constructor.x` or
  `data-bind="$user"`, with the bracket form `$['my key']` named where one reaches it;
  `filter-pattern`, a filter written as a string with flags, `'/^user/i'`, with the literal
  `/^user/i` as the fix; `computed-value`, a value of `data-computed` that cannot be a function;
  and `statement-split`, a `;` inside brackets in a value that is read.
- The pre-1.0 syntax, `data-on-click`, `data-on-load` and `data-bind-value`, which 1.0 ignores,
  is flagged with the 1.0 form as the fix. `data-on:click.prevent` is an error: the client
  listens for an event named `click.prevent`, and the expression never runs on a click.
- Modifiers are judged by what the client does with them. An unknown modifier, an argument on a
  modifier that takes none (`__prevent.foo`) and an unknown flag (`__debounce.150ms.foo`) are
  warnings that Datastar ignores them, and on `data-on` the warning names the event the
  expression really listens for. `__debounce.1.5s`, which the client reads as 1 ms, is an error
  with `__debounce.1500ms` as the fix, and a duration after a flag gets one message with the
  reordered modifier as the fix.
- An attribute with an empty key, such as `data-indicator:`, gets one warning that it does
  nothing, with a fix that drops the colon where the attribute then runs.
- A `data-indicator` with both a key and a value, or with neither, gets only the error Datastar
  raises, and no longer the warning that the indicator tracks no request as well.
- Fewer false findings: `$id` inside a JavaScript string, `@post('/x/$id')`, is a hint about the
  server value it is; a `$my-value` the page declares with `__case.kebab`, or as `{'my-key': 1}`,
  is no longer called unknown, and its fix no longer breaks the page; a key with template syntax
  in it, `data-on:${evt}` or `attributes["data-on:$evt"]`, waits for the rendered page;
  `data-on-signal-patch:filter` is accepted; and `dataSignals("foo-bar", "1")` declares
  `$fooBar`, as the DSL writes it. A signal in the code of a template literal is now read.
- Stream Inspector: it asks and reads as Datastar's client does. It sends the client's `Accept`,
  in the request and in the curl line; reads a 200 that is not HTML, JSON or JavaScript as a
  stream; shows any other response, any status but 200 among them, read up to 1 MiB; and shows
  a closing comment, a message with only a `retry`, and an explicit `event: message`.
- Stream Inspector: the signal store is kept as the client keeps it. A patch the client fails on
  partway shows the keys it kept before the failing one, with the reason beside it, and says
  that the bindings on the page then stop redrawing until it is reloaded. A `__proto__` in a
  patch is refused, and the inspector says why.
- Stream Inspector: your headers replace the client's own, `Content-Type` included, where a
  second one was added. Copy as curl writes the body with `--data-raw`, so signals that start
  with `@` are no longer read from a file. A requests file that is not JSON is not saved over;
  a byte order mark no longer hides what `inspector.json` or `env.json` holds; and a
  requests-file path that leaves the project falls back to the default.
- The inspections take the severity you set in the inspection profile. The inspector shows
  event names, content types and saved names as text, where one that started with `<html>` was
  rendered. The `data-init="@get(...)"` live template is named `data-init-get`.
- Completion keeps actions and modifiers in one order, by name with the Pro ones last, as in
  VS Code; they no longer move up the list as you use them.
- The plugin no longer sets colours for the Default and Darcula schemes, which won over every
  theme built on them. Each Datastar token takes the colour your theme gives a standard key. In
  IntelliJ's own schemes, an action, an attribute key and a modifier argument then show as plain
  text.

## 0.6.1

- Stream Inspector: a stream no longer stops with "Input length = 1" when a character such as
  `æ`, `ø`, `å` or an emoji arrives split across two reads, which a large patch makes likely. A
  byte that is not UTF-8 becomes `�`, as it does in the browser, instead of ending the stream, and
  a byte order mark at the start no longer hides the first event's name.

## 0.6.0

- A `data-indicator` on an element that sends no request is a warning: the indicator only turns on
  for requests its own element sends, so one on a wrapper or a sibling never does.
- Quick fixes: `__debounce_150ms` offers `__debounce.150ms`, since a modifier's arguments follow a dot, and
  `__prevent.stop` offers `__prevent__stop`, since each modifier starts with two underscores.

## 0.5.0

- A signal no file in the project defines is a warning: `data-on:click="$telefon++"` on a page
  whose `data-signals:teller` declares `$teller` is underlined, where only the spell checker had
  something to say before. A defined name that is close, such as `$count` for `$coutn`, is the
  quick fix. Definitions are read from every Kotlin and markup file, as completion reads its
  names; a signal the index cannot see, set from a script, is reported too, and the inspection
  (Streamlord › Signal no file defines) can be turned off.
- Signal completion honours `__case` (`data-signals:my-value__case.snake` offers `$my_value`), and
  reads template files across the project, not only the ones that are open: every extension in
  Settings › Tools › Streamlord's template list, `.njk` and `.vue` included.

## 0.4.0

- Stream Inspector: `{{baseUrl}}` follows the server you started from a run configuration. The
  inspector reads the line the server logs as it starts, Spring Boot's
  `Tomcat started on port 9102 (http) with context path '/'` or Ktor's `Responding at …`, so
  the port and context path are right wherever they were set. `.streamlord/env.json` still comes
  first, and the default only when nothing runs; the Variables box says which it is.
- Stream Inspector: when the env file's `baseUrl` is refused and a running server said it
  started elsewhere, the error says where and offers **Use … in .streamlord/env.json**, which
  changes that value and nothing else in the file.
- Stream Inspector: an error response shows the warnings and errors the server logged while
  answering it, such as Spring's `Required request parameter 'partsnummer' … is not present`,
  in place of the note that the reason is in the log.
- Stream Inspector: `localhost` is tried as `::1` when `127.0.0.1` refuses, as curl and the
  browser do. A server listening on the IPv6 loopback alone was reported as not running.
- Open in Stream Inspector writes the whole URL the handler needs. Each path parameter and each
  required `@RequestParam` becomes a `{{name}}`, and each required `@RequestHeader("X-Id")` a
  line in the headers, so `@GetMapping("/visning")` with a required `partsnummer` opens as
  `{{baseUrl}}/api/hent/visning?partsnummer={{partsnummer}}`. The icon names the optional
  ones it leaves out. The dialog that asked for `{id}` is gone.
- Stream Inspector: `.streamlord/env.json` holds the values in `params`, so they are kept between
  runs and stay out of the shared `inspector.json`. A param that is not there yet is reported
  with a link that adds it, empty, for you to fill in; one left empty is reported until it is
  filled. In the URL its value is encoded, in the headers it is sent as written. `{{` offers
  them in the URL and the headers, and the JSON schema knows the key.
- Stream Inspector: an env file that is not valid JSON says where, such as `at line 3, column 1`
  for a comma after the last value.
- Open in Stream Inspector: a Spring or Ktor mapping without its leading slash, such as
  `@RequestMapping("api/hent")`, opened as `{{baseUrl}}api/hent/visning`, which the HTTP client
  refused as "unsupported URI". The path now always starts with `/`.
- Stream Inspector: a URL whose host or port cannot be read is refused before sending, with what
  is wrong: `The port in localhost:8080api is not a number. Is a / missing between the port and
  the path?`, or a host name such as `my_service`.
- Stream Inspector: an error response without a body, such as the bare `400` Spring Boot sends
  when a request parameter is missing, carries a note that the reason is in the server's log.

## 0.3.0

- Stream Inspector: variables live in `.streamlord/env.json` alone, and there are three:
  `baseUrl` (text, for the URL), `signals` (an object, for the signals field) and `headers`
  (an object of texts, one `Name: value` line in the headers field). Any other key, or a value of
  the wrong kind, is reported by name instead of silently dropped, and the file has a JSON
  schema. Typing `{{` in the URL, signals or headers offers the variables that are set. The
  Variables box shows each value, marking a default `baseUrl`, and **Edit variables** opens the
  file, creating it with `baseUrl` filled in. A refused connection says which `{{baseUrl}}` it went to
  and where that value came from. The variables in the settings are gone; only the default base
  URL setting remains.
- Stream Inspector: the "edit file" button is gone. Saved requests are picked from the Request
  list, whose first entry now says how many saved and recent requests it holds.
- Stream Inspector: the request goes out as the curl line shows it. The signals are no longer
  written anew (`\u00e9` was sent as `é` and `</` as `<\/`), and a header the HTTP client will
  not set, such as `Host`, is reported as such instead of as an invalid URL. A URL that cannot be
  sent as written, such as a relative one, one that still holds `{id}` or one with a space, is
  refused with the reason and the way to write it, in the same words in both editors.
- `patchElements("#rows") { li { } }`, the kotlinx.html form, had its selector read as markup
  and flagged as text outside an element, and a mode without a selector went unreported. With a
  trailing lambda the first string is the selector and the second argument the mode.
- Spring routes: a method took the `@RequestMapping` prefix of the class before it in the
  file. A class without `@RequestMapping` now has no prefix, a nested class keeps its mapping to
  itself, and a class mapping is no longer reported as a route when another annotation with
  parentheses in a string, such as `@PreAuthorize("hasRole('ADMIN')")`, stands before the class.

## 0.2.0

- Ordinary attributes such as `data-id`, `data-test` and `data-href` are no longer flagged as
  misspelt Datastar attributes. A bare name is judged only when it is a long Datastar name with a
  letter swapped, such as `data-indicater`.
- Valid markup that was refused is accepted: optional end tags (`<li>a<li>b`), `__debounce.500`,
  `$foo.0.name`, HTML entities in an attribute value, and an `@` inside a string or a comment.
- A string followed by `.trimIndent()` or `.trimMargin()` is checked like any other argument, and
  a positional selector in `patchElements` is seen.
- New errors for what the Datastar client refuses at run time: `@get ('/x')` with a space, an
  attribute that needs a value and has none, and `data-bind` with both a key and a value.
- The quick fix for a hyphenated signal keeps an escaped dollar escaped.
- Both editors now run one shared corpus of cases, and agree on every one.
- The JavaScript check is a port of acorn, the parser the VS Code extension uses, so the two
  editors give the same verdict on an expression.

## 0.1.1

- The plugin icon fills its tile. The mark was drawn at half the width of the square and sat low
  in it, which at the size a marketplace listing shows it reads as a small badge in a large dark
  square. Same drawing, scaled about its own centre and centred in the tile.
- A hyphen after a signal is read by Datastar 1.0.4 as part of the name (`$count-1` is
  `$['count-1']`, only `$a-$b` is a subtraction); the warning says so, covers the cases it
  excused before, and offers the spaced subtraction beside the camelCase signal.

## 0.1.0

The first forging. What the VS Code extension does, in IntelliJ IDEA 2026.1 and newer, Community
and Ultimate, through the shared `streamlord-analysis` module.

- Inspections for Datastar expressions and markup in Kotlin strings (handed to the DSL or
  free-standing) and in HTML and template files: the Kotlin `$` interpolation trap, expression
  syntax with the error at the right column, unknown and Pro actions, unknown attributes and
  modifiers with "did you mean", modifier arguments, keys, elements, missing ids, modes that need
  a selector, blank selectors, `</script` in scripts, capitals in keys and `$foo-bar` signals,
  and what the DSL writes on the wire. Five inspections, one per family, each with its own
  severity. A call the Kotlin plugin resolves to something other than Streamlord is left alone.
- Quick fixes on Alt+Enter for every diagnostic that has one right answer, including the `$$`
  literal rewrite of a whole string.
- Completion for signals (project-wide, this file first), actions, attributes, modifiers and
  their values, ids and classes in selector arguments; the popup opens on `$`, `@`, `#`, `:`
  and `_`. Attribute names in HTML files yield to the official Datastar plugin when it is
  installed.
- Documentation on hover for attributes, modifiers, actions and the DSL functions.
- Highlighting of Datastar tokens in expressions and of the parts of a `data-*` attribute name,
  with a colour settings page and palettes for the light and dark schemes that touch nothing
  else.
- HTML injected into Kotlin strings that open with a tag, without `@Language("HTML")`.
- Live templates for Ktor and Spring routes, the DSL calls, `htmlFunction`, and every `data-*`
  attribute, the latter generated from the catalog.
- The Stream Inspector as a tool window: live decoded SSE events with the signal store, saved
  requests in `.streamlord/inspector.json` (shared with VS Code, with its JSON schema), recent
  requests, `{{variables}}` from the settings and `.streamlord/env.json`, gutter icons on Ktor
  and Spring routes, curl export, non-SSE responses with their `datastar-*` headers.
