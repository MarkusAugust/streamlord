# Streamlord for IntelliJ IDEA

> *The forge is one thing. The eye that sees the flaw before the blade is quenched is another.
> I have both, and I lend them to you for nothing. Ask yourself why.*
>
> Sarn the Faceless

<!-- description -->
Datastar 1.0.4 as a language in IntelliJ IDEA (Community and Ultimate), 2026.1 and newer. Half of
the [Streamlord](https://github.com/MarkusAugust/streamlord) toolchain, and the half that needs no
part of the other: it reads your HTML and template files whatever answers them, and your Kotlin
strings alongside the Streamlord SDK.

**Inspections** in Kotlin strings, whether passed to a Streamlord call or free-standing (a
function that returns HTML, a `val` holding a fragment), and in HTML and template files:

- Kotlin interpolation of signals: `"$count++"` becomes `"++"` at runtime. An error with the fix
  spelled out. In HTML strings, `$name` inside a `data-*` expression is a warning, a weak warning
  when `name` is a number or a boolean, which is safe written in.
- A Kotlin value in an HTML string, judged by its Kotlin type and where it lands, as `interpolate`
  judges it at run time: in text or a quoted attribute, where escaping saves it, a weak warning (a
  warning for a `String`) with a fix that rewrites the literal as `interpolate(...)`; at the start
  of a URL, in `style`, a `data-*` expression, a script or an event handler, a warning, since
  escaping cannot help there. Constants, literals, numbers and booleans where they are safe, Kotlin enum
  entries and markup built in the template say nothing. A call to `interpolate` is judged too: a
  miscounted `%s` and a value it will refuse are errors, a Kotlin template in its format a warning
  with a fix that makes it a `%s` hole.
- Datastar expression syntax, checked with a real JavaScript parser, with the error at the right
  column.
- Unknown actions (`@Post`), Pro actions and Pro attributes (they need the Pro bundle).
- An empty `data-nonce` on `<html>`, which makes Datastar throw `NonceRequired` and not start,
  is an error; one that is no base64 value, which a browser ignores in the policy, a warning; one
  a template fills in says nothing. `dataNonce` of such a literal, which throws as the page
  renders, is an error either way.
- Markup in `patchElements(...)`: unclosed or stray tags, text outside elements, a missing `id`
  on a top-level element when no selector is given, a mode that needs a selector.
- `data-*` attributes: unknown names with "did you mean", unknown modifiers, wrong modifier
  arguments (`__debounce` without a duration, `__threshold.abc`), missing or unexpected keys,
  and a `data-indicator` on an element that sends no request.
- Casing: a capital letter in a key (`data-signals:fooBar`), which the browser lowercases so the
  signal becomes `$foobar`, and a hyphen after a signal in an expression (`$foo-bar`,
  `$count-1`), which Datastar swallows into one signal name. Both come with the fix. In the DSL
  the SDK writes a camelCase key correctly on its own, and a weak warning on the call shows what
  goes on the wire. A hyphen in a signal name written in a DSL call, `dataSignals("foo-bar", …)`
  or `signal("form.first-name")`, is an error, since the SDK refuses it at runtime, with the
  camelCase fix.
- What Datastar's own rewrite of an expression does that JavaScript would not: a `$name` or
  `@name(` in the text of a template literal that also holds a `${...}`, or inside a regular
  expression, is rewritten there too; a `'` in a comment, a regular expression or a template
  pairs with a later quote, and the `$name` between them stays a JavaScript variable; `$år` is no
  signal, since only ASCII follows a `$`; and `@get` without its parenthesis is a syntax error.
- Signal names Datastar's client cannot hold or no `$name` reaches: `__proto__` anywhere in the
  name, `constructor` or another property of `Object.prototype` with more of the name or an object
  under it (`data-signals:constructor.x`), also once `__case` has turned the key into it, a `$` in
  `data-bind="$user"`, and a letter beyond ASCII, where the message names the bracket form,
  `$['år']`, that does reach it. `data-signals:constructor="1"` is held, and says nothing.
- Values the client evaluates or splits: a filter of `data-json-signals` or
  `data-on-signal-patch-filter` that does not compile or reads a signal, a pattern written as a
  string with flags after its last slash, `'/^user/i'`, there or in the `filterSignals` of an
  action, where the client cuts one `/` from each end and matches the `/i` as text (`'/^user/'`
  works as it is), `data-computed` without a key
  whose value is sure to be no function, and a `;` inside brackets in a value that is read, where
  the client splits it and loses the value.

A string counts as HTML when it opens with a tag, a comment or a doctype, or when it carries
`@Language("HTML")` or a `// language=HTML` comment. Multi-dollar literals (`$$"""..."""`,
Kotlin 2.2+) are read as Kotlin reads them: a single `$` is text, so `$count` there is a signal.
A call the Kotlin plugin resolves to something other than Streamlord is left alone.

**Quick fixes** on Alt+Enter: `$count` becomes `increment("count")`, `signal("count")`,
`toggle("open")`, a `$$` literal or an escaped `${'$'}count`; `__debunce` becomes `__debounce`,
`__debounce_150ms` becomes `__debounce.150ms` and `__prevent.stop` becomes `__prevent__stop`;
`@Post` becomes `@post`; `data-signal` becomes `data-signals`; a missing duration, `id` or
`selector` is added. Every message links to the matching page of the Datastar reference.

**Completion**: `$` offers every signal name declared anywhere in the project (Kotlin DSL calls,
`@Serializable` classes, HTML strings and templates), this file's first; `@` offers actions;
`data-` offers attributes; `__` offers modifiers and their values; `#` and `.` in any `selector`
argument offer the ids and classes declared in the project; in the options object of a backend
action, `@get('/x', {|})`, the options not yet written; after `data-on:`, Datastar's own events
(`datastar-fetch`, `datastar-signal-patch`) and the DOM's. A value is read as the browser reads
it, so all of this works over several lines and in single quotes, and in a Kotlin string a `\$`
or `${'$'}` before a signal stays as written. When the official Datastar plugin is installed, the
attribute names and DOM events in HTML files are left to it, so nothing is listed twice;
Datastar's own events, which it does not know, are offered all the same.

**Signal navigation**: Ctrl+click (⌘+click on macOS) or Go to Declaration on `$count` goes to every
place the project defines it: a `data-signals` on the page, a `patchSignals` in a handler, a
property of a signals class. Find Usages (Alt+F7) lists those places and every expression that reads
the signal, in HTML files, Kotlin strings and the templates IntelliJ gives an HTML tree; a template
it reads as plain text has none yet. Rename (Shift+F6) rewrites them all, each as its place needs:
renaming `itemCount` to `basketCount` writes the key `data-signals:basket-count`, since the browser
lowercases a key. A place that cannot hold the new name stops the rename before anything is written:
a key whose `__case` gives no spelling of it back, a key of an object asked to move under another
object, a property of a signals class, which Kotlin's own rename renames. In `$user.name.length`
each part is its own signal, so the caret on `user` renames `user` and on `name` renames
`user.name`. The places come from the plugin's index, so a search reads no file again.

**Parameter info** (⌘P on macOS, Ctrl+P elsewhere, and on typing `(` or `,`) inside an action call,
in HTML files, templates and Kotlin strings alike: `@get(uri, options?)`, with the argument the
caret is on. Inside a Datastar string in a Kotlin call it stands in for the Kotlin plugin's help
with the call around the string. In Kotlin the help starts once the DSL call's parenthesis is
closed, as the IDE closes it for you.

**Documentation** on hover (F1 on macOS, Ctrl+Q elsewhere) for every attribute, modifier, action
and option of a backend action, in HTML files, templates and HTML strings in Kotlin alike, and for
the DSL functions where the Kotlin plugin has no KDoc to show.

**Spelling**: in a Datastar expression, in HTML and in the DSL calls and HTML strings of Kotlin,
only the strings are checked, so a signal such as `$usrnm` is no typo and `'Sav'` still is. A
template file IntelliJ reads as plain text is still spelled whole.

**Highlighting** of Datastar expressions inside DSL strings and HTML strings, and of `data-*`
attributes in HTML: prefix, plugin, key, modifiers and modifier arguments each get their own
colour, and the attribute value is coloured as a Datastar expression. The plugin brings no colours
of its own: each token takes the colour your theme gives a standard one, a signal that of a static
field, a backend action such as `@get` that of a keyword, and any other action that of a function
call. IntelliJ's own schemes give a function call and a class name no colour, so there an action,
an attribute key and a modifier argument read as plain text. Settings | Editor | Color Scheme |
Streamlord lets you give any of them one.

**HTML injection** into Kotlin strings that open with a tag but carry no `@Language("HTML")`, so
tags complete and close as in an HTML file. Streamlord's own parameters carry the annotation
already.

**No namespace warning** on a key: IntelliJ reads the colon in `data-on:click` as an XML
namespace and warns that `data-on` is not bound. In an HTML file that warning is gone for every
Datastar attribute, and for `data-star-on:click` when the Attribute prefix in Settings | Tools |
Streamlord is `data-star-`. A name such as `foo:bar` is still reported, and so is a Datastar key
in XHTML, where a parser that reads the page as XML (a browser given it as XML, or JSF Facelets)
stops at it.

**Live templates** for Ktor and Spring routes and the DSL calls in Kotlin, for a function that
returns HTML as a `$$"""..."""` string (`htmlFunction`), and for every `data-*` attribute in
HTML (`data-on` Tab).

**Template languages**: the HTML side runs wherever IntelliJ shows an HTML tree: `.html`
(Thymeleaf), and with their plugins FreeMarker, Velocity, JTE, Mustache, Pebble and the rest.
The engine's own syntax (`${...}`, `!{...}`, `{{...}}`, `@if`, `#if`, `<#if>`, `[#if]`) is left
alone: an attribute value that contains it cannot be judged before rendering.

**Stream Inspector** (the Streamlord tool window): connect to a running endpoint with a method,
signals and headers; every SSE event arrives decoded, and the signal store is kept exactly as
the client would keep it.
It asks as Datastar's client does, with the same `Accept`, and reads what the client reads: a
200 that is not HTML, JSON or JavaScript is decoded as a stream, whatever its `Content-Type`. Any
other response is shown with its `datastar-*` headers and up to 1 MiB of its body; a
`text/event-stream` response with any status but 200 is not read, as the client reads none.

- **Saved requests** live in `.streamlord/inspector.json` in the project, the same file the VS
  Code extension uses (commit it to share with the team, or ignore it). Save one with **Save**,
  and pick it again from the Request list, which also holds the recent ones and says how many
  of each it has. **Delete** removes the selected one.
- **Recent** requests are remembered automatically, and the last one is prefilled on open.
- **Variables** live in `.streamlord/env.json` in the project: three with a field of their own,
  and the `params` of your routes:

  ```json
  {
    "baseUrl": "http://127.0.0.1:8081",
    "signals": { "search": "ash" },
    "headers": { "Authorization": "Bearer dev-token" },
    "params": { "partsnummer": "3000507723", "instans": "m1" }
  }
  ```

  | Variable | Use it in | Becomes |
  |---|---|---|
  | `{{baseUrl}}` | the URL | the text, without a trailing slash |
  | `{{signals}}` | the signals field | the object, as written in the file |
  | `{{headers}}` | the headers field | one `Name: value` line per entry |
  | `{{partsnummer}}`, any name in `params` | the URL or the headers | the text, encoded in the URL |

  Type `{{` in a field to pick from the ones that are set. Without `baseUrl` in the file,
  `{{baseUrl}}` is where the server you started from a run configuration said it listens, read
  from its own log (`Tomcat started on port 9102 (http) with context path '/'`, or Ktor's
  `Responding at …`), so the port and context path are right wherever they were set. With
  nothing running, it is the Default base URL setting, `http://localhost:8080` unless you change
  it.

  The Variables box in the panel lists each value, and says when `baseUrl` is the default or
  which run configuration it came from.
  **Edit variables** opens the file, creating it with `baseUrl` filled in, and the panel picks
  up the file as you save it. Any other key, or a value of the wrong kind, is reported by name
  and stops the request until it is fixed; the file also has a JSON schema, so the editor marks
  it as you type. The file is the place for local hosts, tokens and test values, so keep it out
  of version control. A server that cannot be reached is reported together with the `{{baseUrl}}` it was
  reached through; when the file's `baseUrl` is not where a running server said it started, a
  link sets the file's value to that one, changing nothing else. `localhost` is tried as `::1`
  too, as curl and the browser do. An error response is shown with the warnings and errors the
  server logged while answering it, such as Spring's `Required request parameter 'partsnummer'
  … is not present`.
- **Gutter icons** on every Ktor route (`route("/api") { get("/feed") }`) and Spring mapping
  (`@GetMapping("/feed")` under a class `@RequestMapping`): "Open in Stream Inspector" prefills
  the method and the whole URL the handler needs: each path parameter and each required
  `@RequestParam` as a `{{name}}`, and each required `@RequestHeader("X-Id")` as a line in the
  headers. A value that is not in `params` yet is reported with a link that adds it, empty, for
  you to fill in; the icon names the optional ones, such as `required = false`, which it leaves out.
- **Copy as curl** puts an equivalent `curl -N ...` command on the clipboard.

Everything the plugin knows comes from `catalog/datastar-1.0.4.json`, which the SDK's own tests
bind to the DSL, through `streamlord-analysis`, the same checks the VS Code extension makes.
<!-- /description -->

## Settings

Settings | Tools | Streamlord:

| Setting | Default | Meaning |
|---|---|---|
| Attribute prefix | `data-` | `data-star-` when you load the aliased bundle. |
| Inject HTML into Kotlin strings | on | Strings that open with a tag, without `@Language("HTML")`. |
| Leave attribute names to the Datastar plugin | on | No double listing of attribute names and DOM events in HTML files when the official plugin is installed. |
| Default base URL | `http://localhost:8080/` | `{{baseUrl}}` unless `.streamlord/env.json` sets `baseUrl`. |
| Saved requests file | `.streamlord/inspector.json` | Project-relative. |
| Gutter icons on routes | on | "Open in Stream Inspector" on Ktor and Spring routes. |

The inspections are under Settings | Editor | Inspections | Streamlord, one per family
(interpolation, expression, attribute, signal no file defines, key casing, patch markup), each
with its own severity. A severity you set there applies to the issues at the family's own level
or above, and never raises one below it: setting the interpolation inspection to a warning makes its errors
warnings and leaves its weak warnings weak.

## Datastar Pro

The plugin knows the publicly documented names of Pro attributes and actions so it can complete
and validate them. It contains no Pro code and cannot make them work: that takes the Pro bundle
you license and load yourself.

## Development

```
./gradlew test             # platform tests: inspections, fixes, completion, hover, colours, injection, the inspector
./gradlew runIde           # a sandboxed IntelliJ IDEA with the plugin
./gradlew buildPlugin      # build/distributions/streamlord-intellij-<version>.zip
./gradlew verifyPlugin     # the Plugin Verifier against the recommended IDE releases
```

The plugin build includes the SDK build (`includeBuild("../..")`) for `streamlord-analysis`, the
editor-independent analysis that the VS Code extension mirrors in TypeScript; both are tested
against the same cases. The analysis module's tests live with the SDK: `../../gradlew
:streamlord-analysis:test`.

### Publishing

The plugin is published to the JetBrains Marketplace from CI only, never from a developer
machine. Bump `pluginVersion` in `gradle.properties`, add the release to `CHANGELOG.md`, commit,
then push a tag `intellij-v<version>`. The `publish-intellij` job checks that the tag matches,
builds, tests, verifies against the supported IDE releases and runs `publishPlugin` with the
`JETBRAINS_MARKETPLACE_TOKEN` secret of the `release` environment (a Marketplace permanent token
for the `MarkusAugust` vendor). `JETBRAINS_CERTIFICATE_CHAIN`, `JETBRAINS_PRIVATE_KEY` and
`JETBRAINS_PRIVATE_KEY_PASSWORD` sign the archive, and all three are required: when one is
missing, the job stops before `publishPlugin` rather than upload an unsigned plugin. Which branch
the bump goes on, and when the tag may be pushed, is in
[the repository README](https://github.com/MarkusAugust/streamlord#building-and-releasing).
