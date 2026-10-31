# Changelog

All notable changes to the Streamlord extension are recorded here.

## Unreleased

- Every Kotlin value written into an HTML string is judged by where it lands, as `interpolate`
  judges it at run time and with core's own reading of the markup: `$name`, `${...}`, a value
  joined with `+`, a literal inside a template and the holes of a `$$` literal, where only `$name`
  was looked at before, and that with the same hint, "make sure it is escaped", wherever it stood.
  `unescaped-interpolation` is a value where escaping saves it, in element text, a quoted attribute
  or a URL whose literal start settles the scheme: a hint, a warning for a `String` and for markup
  outside element text, with a fix that rewrites the literal as `interpolate(...)` and adds its
  import (as `interpolate(...).text` where the literal was a `String`). `unsafe-interpolation` is
  a value where escaping cannot help: the start of a URL, `style`, a `data-*` expression, signal
  path or filter, a script, an event handler or `srcdoc`, a warning, at the start of a URL with
  the fix to build it with `interpolate(...)`, which refuses a URL that runs; a value without
  quotes, a warning (a hint for a number or a boolean) with a fix that quotes it; a tag or
  attribute name, a hint, a warning for a `String` or markup; an end tag, which takes no
  attributes, the same. A `+` chain handed straight to a DSL expression call is judged as one in
  an HTML string is. A constant, a qualified one such as `Routes.SAVE` among them, a literal,
  `${"$"}`, an enum entry, an `if` or a `when` between literals, and markup built in the template
  in element text say nothing.
- A value in a URL the browser loads code from, `src` of a script, an iframe or an embed, `href`
  of a base or `data` of an object, is a warning unless the literal before it already fixes the
  host (`/js/`, or a whole origin such as `https://cdn.example.com/`), and an error in a call to
  `interpolate` where the value is known, since `interpolate` refuses it.
- A call to one of Streamlord's expression helpers is your own only when every argument is: a
  literal, a constant, a declared number or boolean, or another such helper call.
  `${signal("count")}` says nothing; `${signal(query)}` writes the request's text into a Datastar
  expression, where it runs, and is judged as the `String` it returns.
- A `String` in element text, which may be text from outside or markup you built, gets two fixes
  and neither first: the `interpolate(...)` rewrite, which escapes it, and "Mark … as markup",
  which wraps it in `Trusted(...)` and adds the import. The message says to declare markup `Html`
  or wrap it.
- A call to `interpolate` is judged as it will run. A `%s` count that does not match the values is
  the error `interpolate-holes`, since the call throws. A `%s` whose value `interpolate` will
  refuse where it lands is `unsafe-interpolation`, an error where the value is known to be refused
  (a `String` in a `data-*` expression, a `Boolean` in `style`) and a warning where it is not. A
  Kotlin template written into the format is `interpolate-template`, a warning, since Kotlin fills
  it in before `interpolate` sees it, with a fix that makes it a `%s` hole and passes its value in
  its place. A `%s` in element text given markup held as a `String`, `h.text` or a `val` set to
  one, is `interpolate-template` too, a warning, since `interpolate` escapes it and its tags are
  printed as text; a literal is text you chose to have escaped and says nothing. The format is read
  as HTML, so a `%s` in it is a hole and not text.
- The `interpolate(...)` rewrite is offered only where it keeps the page as it was: not for a value
  that may be null, which a template writes as `null` and `interpolate` as nothing, nor for a
  `Trusted` outside element text, nor for a hole in element text that may hold markup, which
  `interpolate` would escape, nor for a value that is a `List`, which a template writes as
  `[a, b]`. The rewrite doubles each per cent sign the literal writes.
- A workspace index of `const val`s: a constant declared in another file is your own, where the
  file does not declare something else of its name. Names are resolved by their scope (lambda and
  loop parameters, local functions, destructuring and constructor properties), and a hole is markup
  only when its whole value is: an HTML literal or a `+` chain that opens with one,
  `interpolate(...)`, `Trusted(...)`, a value declared `Html` or `@Language("HTML")`, kotlinx.html
  output, a `joinToString` over markup, or an `if`, a `when` or an `?:` whose every result is markup
  or a literal.
- In a `data-*` expression of an HTML string, `$count` where `count` is a number or a boolean is a
  hint that asks only whether the signal was meant, since the value is safe written in; it was a
  warning. `kotlin-interpolation` no longer calls a `String` fine there as a server value: only a
  number or a boolean is.
- A template inside a JavaScript string of a Datastar expression, `@post('/x/$id')`, is the warning
  `unsafe-interpolation`: Datastar runs that string as code. It was a hint that called it safe text.
- No more `<p> has no id` inside `<$tag ...>`, whose template is a tag name, and no text fragments
  in the margin of a `trimMargin` literal. A literal that opens with a template tag, `"<$tag>…"`,
  is read as HTML.
- A duration modifier the client reads by its number is a warning that says what it reads, with
  the fix to write it out: `__debounce.5x` waits 5 ms, since `tagToMs` falls back to `parseFloat`,
  and `__delay.0x1fms` 31 ms. It was an error that said a duration was missing. A tag that is no
  number at all, such as `__debounce.soon`, is still that error.
- The markup checks read HTML as the browser does: the content of `xmp`, `iframe`, `noembed`,
  `noframes`, `noscript` and `plaintext` is text, a raw-text element written `<iframe/>` is still
  open, and only its whole end tag closes it, outside svg and math but again inside their HTML
  integration points, such as a `foreignObject`; only HTML's own white space ends an attribute
  name, so a no-break space is part of one; a numeric reference is read whatever its number of
  digits, one to NUL or past U+10FFFF is U+FFFD, and one from 128 to 159 is the windows-1252
  character the browser reads. Where an svg or a math begins and ends follows the browser's tree
  builder for the open elements: an `svg` inside a `math` is a MathML element, so its `desc` holds
  no HTML, unless it opens right in an `annotation-xml`; `mglyph` and `malignmark` right inside a
  MathML text element stay MathML; `<p>`, `<b>`, `<div>` and the other breakout tags, a `<font>`
  with a color, face or size, `</p>` and `</br>`, and the end tag of an HTML element open around
  them close the svg or math, so a `style` after them is HTML's raw text; an end tag inside an
  integration point does not reach past a `div` open in it; and inside an svg or a math a
  `<![CDATA[` section ends only at `]]>`. A tag name runs on past `_` and `.`, so `<style_x>` is no
  style.
- A comment ends where the browser ends it: `<!-->` and `<!--->` are whole comments, and `--!>`
  closes one as `-->` does. The checks used to go quiet until the next `-->`.
- An empty modifier argument is read as the client reads it. After the value it is nothing:
  `__threshold.50.` and `__once.` are fine, and `__case.` has no value. As the first argument it
  is the value: `__debounce.` needs a duration, and the fix takes the place of the dot, while
  `__debounce..500ms` and `__threshold..50` are errors with the fix that drops the empty one,
  offered only when what is left holds up: `__debounce..leading` still lacks a duration.
  `__threshold` is read as the client's `Number` reads it, so `1e1` is 10, no value is 0 with a
  warning, and a value outside 0 to 100 is a warning that the client clamps it. With `__half` or
  `__full` it is a warning that the client never reads it.
- The request inspector puts the `datastar` parameter where the first one stands, also one written
  without a value or percent-encoded, as the client's `URLSearchParams.set` does, and sends and copies as curl a method
  written in lower case as the client does.
- A key the DSL refuses at runtime, one holding `__` or one ending in `_` before any modifier the call
  adds (its `__case`, `ifMissing`, `session` or the modifiers of its lambda), is the error
  `key-refused`, where the hint used to say what it writes on the wire: `dataClass("isOpen_", …)`,
  `dataSignals("foo_", "1", ifMissing = true)` and `dataSignals("fooBar__x", …)` throw. So does a
  `__` in the name of `dataBind`, `dataRef` or `dataIndicator`, and any key with whitespace, a quote
  or a character that ends an attribute. A signal name no `$` reaches, such as `a:b`, `über` or
  `constructor`, is `key-refused` too, in a key, a pair or the name of those three.
- `patch` in an expression of an attribute that does not hand it over is the warning `scope-name`:
  only `data-on-signal-patch` declares `patch`, so `data-text="patch.a"` throws a ReferenceError.
- A pair written `"selected" to null` in `dataSignals` is the warning `signal-null`: Datastar's
  client holds no null, so the pair declares nothing, and an expression that reads the signal
  gets `""`.
- A capital in the key of `dataAttr` or `dataAnimate` is the error `attribute-name-case`, with the
  kebab-case fix, since Streamlord 0.16 refuses it at runtime: the browser lowercases an
  attribute name, so `dataAttr("viewBox", …)` could only set `view-box`. The message names the
  object form, `dataAttr("{viewBox: …}")`, for an attribute that really has capitals.

## 0.10.0

- A hyphen in a signal name written in a DSL call is the error `signal-name-hyphen`, with the
  camelCase fix, since Streamlord 0.15 refuses such a name at runtime: `dataSignals("foo-bar", …)`,
  `dataBind("form.first-name")`, a pair's key, `removeSignals("a-b")`. In `signal`, `set` and the
  other expression helpers a name is read as Datastar's client reads it: a quoted key is text,
  `signal("form['first-name']")`, while `signal("items[\$i-1]")` reads the signal `i-1` and gets
  the advice to write the subtraction in the expression, without a fix. The second argument of
  `dataSignals` is read as the compiler reads it, so the object form, `dataSignals("{...}", true)`,
  is left alone.
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
- The grammar marks `$123` and `$foo-bar` as one signal each, as the client reads them.
- Completion, hover and the checks know the object form of Pro's `data-animate`,
  `data-animate="{width: $w + 'px'}"`, and its modifiers `duration`, `delay`, `ease`, `loop` and
  `pingpong`, and the `delay`, `debounce` and `viewtransition` modifiers of `data-on-raf` and
  `data-on-resize`. A `data-animate` without a key is no longer flagged, and its snippet inserts
  the object form; type `data-animate:` for the keyed one.

## 0.9.1

- Security: the extension no longer hangs on a line such as `<a != != … '`. Finding the attribute
  name being typed took time exponential in the line's length (CodeQL `js/redos`), so 400
  characters before the cursor could keep the extension host busy for minutes. The tag is now
  read in one pass; completion offers the same names it did.

## 0.9.0

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
  `$fooBar`, as the DSL writes it. A signal inside a regular expression is no longer read.
- Stream Inspector: it asks and reads as Datastar's client does. It sends the client's `Accept`,
  in the request and in the curl line; reads a 200 that is not HTML, JSON or JavaScript as a
  stream; shows any other response, any status but 200 among them, read up to 1 MiB; and shows
  a closing comment, a message with only a `retry`, and an explicit `event: message`. A `retry`
  too large for a 32-bit int shows as none, where the previous one was kept.
- Stream Inspector: the signal store is kept as the client keeps it, by the same rules as the
  server SDK's, so a `length` key turns into a number as in the browser and a string patch is
  split by UTF-16 unit. A patch the client fails on partway shows the keys it kept before the
  failing one, with the reason beside it, and says that the bindings on the page then stop
  redrawing until it is reloaded. A `__proto__` in a patch is refused, and the inspector says why.
- Stream Inspector: your headers replace the client's own, `Content-Type` included, where yours
  was overridden. Copy as curl writes the body with `--data-raw`, so signals that start with `@`
  are no longer read from a file. Signals written as an object in `inspector.json` are sent as
  written, where a number past 2^53 was rounded. A requests file that is not JSON is not saved
  over; a byte order mark no longer hides what `inspector.json` or `env.json` holds; and a
  requests-file path that leaves the workspace falls back to the default.
- "Open Stream Inspector with route" is gone from the command palette, where it failed without a
  route. "Apply recommended colors" reads your user settings alone, so a workspace's token rules
  are no longer copied into them.
- The package carries acorn's licence in `ThirdPartyNotices.txt`.

## 0.8.0

- A `data-indicator` on an element that sends no request is a warning: the indicator only turns on
  for requests its own element sends, so one on a wrapper or a sibling never does.
- Quick fixes: `__debounce_150ms` offers `__debounce.150ms`, since a modifier's arguments follow a dot, and
  `__prevent.stop` offers `__prevent__stop`, since each modifier starts with two underscores.

## 0.7.0

- A signal no file in the workspace defines is a warning: `data-on:click="$telefon++"` on a page
  whose `data-signals:teller` declares `$teller` is underlined. A defined name that is close, such
  as `$count` for `$coutn`, is the quick fix. Definitions are read from every Kotlin and HTML
  file, as completion reads its names, and the check waits until the workspace has been read
  once. A signal set only from a script is reported too; `streamlord.diagnostics.unknownSignals`
  turns the check off.
- Signal completion honours `__case` (`data-signals:my-value__case.snake` offers `$my_value`), and
  reads template files (`.jte`, `.peb`, `.hbs`, `.njk` and the rest) across the workspace, not
  only the ones that are open. Files renamed, deleted or changed outside the editor are read
  again; a git diff or an untitled buffer is not read at all.

## 0.6.0

- Stream Inspector: `{{baseUrl}}` follows the server you started from a debug session. The
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
- Open in Stream Inspector writes the whole URL the handler needs. Each path parameter and each
  required `@RequestParam` becomes a `{{name}}`, and each required `@RequestHeader("X-Id")` a
  line in the headers, so `@GetMapping("/visning")` with a required `partsnummer` opens as
  `{{baseUrl}}/api/hent/visning?partsnummer={{partsnummer}}`. The lens names the optional
  ones it leaves out. The dialog that asked for `{id}` is gone.
- Stream Inspector: `.streamlord/env.json` holds the values in `params`, so they are kept between
  runs and stay out of the shared `inspector.json`. A param that is not there yet is reported
  with a link that adds it, empty, for you to fill in; one left empty is reported until it is
  filled. In the URL its value is encoded, in the headers it is sent as written. `{{` offers
  them in the URL and the headers, and the JSON schema knows the key.
- Stream Inspector: an env file that is not valid JSON says where, such as `at line 3, column 1`
  for a comma after the last value.
- Open in Stream Inspector: a Spring or Ktor mapping without its leading slash, such as
  `@RequestMapping("api/hent")`, opened as `{{baseUrl}}api/hent/visning`. The path now always
  starts with `/`.
- Stream Inspector: a URL whose host or port the IntelliJ inspector cannot read is refused here
  too, with what is wrong: `The port in localhost:8080api is not a number. Is a / missing between
  the port and the path?`, or a host name such as `my_service`.
- Stream Inspector: an error response without a body, such as the bare `400` Spring Boot sends
  when a request parameter is missing, carries a note that the reason is in the server's log.

## 0.5.0

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
- Stream Inspector: the request goes out as the curl line shows it. The query string and the
  signals are no longer written anew (`?flag` was sent as `?flag=`, `a%20b` as `a+b` and `1.0` as
  `1`), and headers are read as for the curl line. A URL that cannot be sent as written, such as a
  relative one, one that still holds `{id}` or one with a space, is refused with the reason and
  the way to write it, in the same words in both editors.
- `patchElements("#rows") { li { } }`, the kotlinx.html form, had its selector read as markup
  and flagged as text outside an element, and a mode without a selector went unreported. With a
  trailing lambda the first string is the selector and the second argument the mode.
- Spring routes: a method took the `@RequestMapping` prefix of the class before it in the
  file. A class without `@RequestMapping` now has no prefix, a nested class keeps its mapping to
  itself, and a class mapping is no longer reported as a route when another annotation with
  parentheses in a string, such as `@PreAuthorize("hasRole('ADMIN')")`, stands before the class.

## 0.4.0

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
- A quick fix for an expression inside an HTML attribute edited the start of the tag instead of
  the expression.

## 0.3.3

- The icon fills its tile. The mark was drawn at half the width of the square and sat low in it,
  which on a marketplace listing reads as a small badge in a large dark square. Same drawing,
  scaled about its own centre and centred in the tile.

## 0.3.2

- A hyphen after a signal is read by Datastar 1.0.4 as part of the name: `$foo-bar` is
  `$['foo-bar']`, `$count-1` is `$['count-1']` and `$total-el.offsetWidth` is
  `$['total-el']['offsetWidth']`; only `$a-$b` is a subtraction. The warning now says so, covers
  `$count-1` and the scope-variable cases it excused before, and offers the spaced subtraction
  (`$count - 1`) beside the camelCase signal. The old wording, "reads as `$foo` minus `bar`",
  described a JavaScript that never ran.

## 0.3.1

- An expression that opens with `{` is read as the object literal Datastar makes of it, which
  wraps the last statement in `return (...)`: `data-signals="{count: 0, name: ''}"` no longer
  draws a syntax error. To a script parser it was a block.

## 0.3.0

- The wire hint also covers `dataBind`, `dataRef` and `dataIndicator` once a `case` is given
  (named, positional or in the trailing lambda), since the SDK then moves the name into the
  key: `dataRef("myRef", Case.CAMEL)` says `data-ref:my-ref__case.camel`.
- HTML strings that are not handed to a Streamlord call get the HTML side too: a function
  that returns markup, a `val` with a fragment. A string counts as HTML when it opens with a
  tag, a comment or a doctype, or when `@Language("HTML")` or `// language=HTML` sits just
  before it. Diagnostics for the `data-*` attributes and their expressions, completion of
  attributes, modifiers, signals and actions, hover documentation and syntax highlighting.
- Kotlin interpolation inside a `data-*` expression in an HTML string (`data-text="$count"`,
  `data-signals="{count: $initial}"`) is a warning with the `$$` and escape fixes: a server
  value there works (`data-show="true"`) as often as it is the trap; in element text it stays
  a hint. In the DSL's own expression arguments (`dataText("$count")`) it remains an error.
- The `$$` fix uses one more dollar than the longest run already in the literal, so a `$$name`
  template in a plain string becomes text under a `$$$` literal, as the fix promises.
- New quick fix on every `$` interpolation trap: "Make it a `$$` literal", which rewrites the
  whole literal so the flagged `$name` stays a signal, every other template becomes `$$name`
  and stays Kotlin, and `${'$'}` becomes a plain dollar. Offered right after the DSL helper.
- Multi-dollar literals (`$$"..."`, `$$"""..."""`, Kotlin 2.2+) are read as Kotlin reads them:
  a single `$` is text, only `$$name` is a template. No interpolation diagnostics there, since
  there is no trap; the expression, markup and attribute checks, completion and highlighting
  apply as in any other string, including as an argument of the DSL calls.
- Signal names declared in HTML strings in Kotlin (`data-signals:draft`, `data-bind="search"`)
  feed signal completion.
- Template languages of the JVM: JTE and kte, FreeMarker, Velocity and Mustache are on the
  default language list, and their syntax is understood: `!{...}`, `@if`/`@for`, `#if`/
  `#foreach`, `<#if>`, `</#list>`, `<@macro>`, `<#-- -->`, `<%-- --%>`, `[#if]`, `[=x]`, and
  Thymeleaf's `*{...}`, `#{...}` and `@{...}`.
- Snippet `htmlFunction`: a function returning HTML as a `$$"""..."""` string under
  `@Language("HTML")`.
- Casing. A capital letter in an attribute key (`data-signals:fooBar`, `data-on:widgetLoaded`,
  `data-class:isOpen`, `data-attr:ariaLabel`) is a warning, since the browser lowercases it
  and Datastar would name the signal `$foobar`; the quick fix writes the kebab-case key that
  comes back as the intended name, with `__case.camel` or `__case.pascal` where needed. A
  `$foo-bar` in an expression, which reads as `$foo` minus `bar`, is a warning with `$fooBar`
  as the fix. An explicit `__case` does not excuse a capital in the key, since the browser
  lowercases it first; the fix keeps the modifier and kebab-cases the key. Hover documentation
  of every keyed attribute states its casing rule, read from `keyCase` in the catalog. In the DSL
  the SDK writes a camelCase key correctly by itself; a hint on the call says what goes on the
  wire (`dataSignals("fooBar", ...)` is `data-signals:foo-bar`), so nothing is hidden and the
  rule is learned where it applies.

## 0.2.0

- Quick fixes for the diagnostics that have one right answer: Kotlin `$` interpolation (DSL
  helper or escape), unknown modifiers, actions and attributes, missing duration, missing `id`,
  missing `selector`.
- Every diagnostic links to the Datastar reference.
- Snippets for Kotlin (routes and DSL calls) and HTML (every `data-*` attribute), the HTML
  ones generated from the catalog.
- HTML custom data: Datastar attributes complete and hover through VS Code's own HTML service.
- The HTML side runs in template languages too, configurable with `streamlord.languages`.

## 0.1.0

The first forging.

- Diagnostics for Datastar expressions and markup in Kotlin DSL strings and `.html` templates:
  Kotlin `$` interpolation traps, expression syntax, unknown actions and modifiers, missing ids,
  modes that need a selector.
- Completions for signals, actions, attributes, modifiers, ids and classes; hover documentation.
- Syntax highlighting of Datastar expressions and HTML inside DSL strings and of `data-*`
  attributes in HTML, with recommended dark and light palettes for Datastar tokens only.
- Stream Inspector: live decoded SSE events with the signal store, saved requests in
  `.streamlord/inspector.json`, recent requests, `{{variables}}`, route code lenses for Ktor
  and Spring, and curl export.
