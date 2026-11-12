# Streamlord for VS Code

> *The forge is one thing. The eye that sees the flaw before the blade is quenched is another.
> I have both, and I lend them to you for nothing. Ask yourself why.*
>
> Sarn the Faceless

Datastar 1.0.5 as a language in your editor. Half of the
[Streamlord](https://github.com/MarkusAugust/streamlord) toolchain, and the half that needs no
part of the other: it reads your HTML and template files whatever answers them, and your Kotlin
strings alongside the Streamlord SDK.

## What it does

**Diagnostics** in Kotlin strings, whether passed to a Streamlord call or free-standing (a
function that returns HTML, a `val` holding a fragment), and in `.html` and template files:

- Kotlin interpolation of signals: `"$count++"` becomes `"++"` at runtime. Flagged as an error
  with the fix spelled out. In HTML strings, `$name` inside a `data-*` expression is a warning,
  a hint when `name` is a number or a boolean, which is safe written in.
- A Kotlin value in an HTML string, judged by where it lands as `interpolate` judges it at run time:
  in text or a quoted attribute, where escaping saves it, a hint (a warning for a `String`) with a
  fix that rewrites the literal as `interpolate(...)`; at the start of a URL, in `style`, a `data-*`
  expression, a script or an event handler, a warning, since escaping cannot help there. Constants,
  including a `const val` anywhere in the workspace, literals and markup built in the template say
  nothing. A call to `interpolate` is judged too: a miscounted `%s` and a value it will refuse are
  errors, a Kotlin template in its format a warning with a fix that makes it a `%s` placeholder.
- Datastar expression syntax, checked with a real JavaScript parser, with the error at the
  right column.
- Unknown actions (`@Post`), Pro actions and Pro attributes (hint: they need the Pro bundle).
- An empty `data-nonce` on `<html>`, which makes Datastar throw `NonceRequired` and not start,
  is an error; one that is no base64 value, which a browser ignores in the policy, a warning; one
  a template fills in says nothing. `dataNonce` of such a literal, which throws as the page
  renders, is an error either way.
- Markup in `patchElements(...)`: unclosed or stray tags, text outside elements, missing `id`
  on top-level elements when no selector is given, a mode that needs a selector.
- `data-*` attributes: unknown names with "did you mean", unknown modifiers, wrong modifier
  arguments (`__debounce` without a duration, `__threshold.abc`), missing or unexpected keys,
  and a `data-indicator` on an element that sends no request.
- Casing: a capital letter in a key (`data-signals:fooBar`), which the browser lowercases so
  the signal becomes `$foobar`, and a hyphen after a signal in an expression (`$foo-bar`,
  `$count-1`), which Datastar swallows into one signal name. Both come with the fix: the
  kebab-case key (plus `__case.camel` for `data-on` and `data-class`), and the camelCase signal
  or the spaced subtraction. In the DSL the SDK writes a camelCase key correctly
  on its own, and a hint on the call shows what goes on the wire. A hyphen in a signal name
  written in a DSL call, `dataSignals("foo-bar", …)` or `signal("form.first-name")`, is an error,
  since the SDK refuses it at runtime, with the camelCase fix.
- What Datastar's own rewrite of an expression does that JavaScript would not: a `$name` or
  `@name(` in the text of a template literal whose `${...}` holds a brace, or inside a regular
  expression, is rewritten there too; a `'` in a comment or a regular expression
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
IntelliJ's injection marker: `@Language("HTML")` on the function or property, or a
`// language=HTML` comment just before it. Multi-dollar literals (`$$"""..."""`, Kotlin 2.2+)
are read as Kotlin reads them: a single `$` is text, so `$count` there is a signal and draws
no interpolation warning. Free-standing strings get the attribute checks of a
template file; the id and completeness rules apply only where the string is handed to
`patchElements` and its kin, because only there is the patch mode known.

**Quick fixes** on the lightbulb: `$count` becomes `increment("count")`, `signal("count")`,
`toggle("open")` or an escaped `${'$'}count`; `__debunce` becomes `__debounce`, `__debounce_150ms`
becomes `__debounce.150ms` and `__prevent.stop` becomes `__prevent__stop`; `@Post` becomes
`@post`; `data-signal` becomes `data-signals`; a missing duration, `id` or `selector` is added;
an HTML string with Kotlin values in it becomes a call of `interpolate(...)`, which is also a
refactoring anywhere in a string that can take it. Every diagnostic links to the matching page of
the Datastar reference, or to Streamlord's page on HTML strings.

**Snippets** for Ktor and Spring routes and the DSL calls in Kotlin, for a function that
returns HTML as a `$$"""..."""` string (`htmlFunction`), and for every `data-*` attribute in
HTML. **HTML custom data** gives VS Code's own HTML service the Datastar attributes with
documentation, so they complete and hover in `.html` files like any other attribute.

**Template languages**: the HTML side (diagnostics, completion, hover) also runs in JTE and
kte, FreeMarker, Velocity, Mustache, Pebble, Twig, Jinja, Django, Razor, PHP, Blade, ERB,
Handlebars, EJS, Liquid, Nunjucks, Edge, Astro, Svelte, Vue and Go templates; Thymeleaf files
are `.html` and need no entry. The list is the setting `streamlord.languages`. The engine's own
syntax (`${...}`, `!{...}`, `{{...}}`, `@if`, `#if`, `<#if>`, `<@macro>`, `[#if]`) is left
alone: an attribute value that contains it cannot be judged before rendering, and directives
are not elements.

**Completions**: `#` and `.` in any `selector` argument offer the ids and classes declared
anywhere in the workspace, this file first; `$` offers every signal name declared anywhere in the workspace (Kotlin DSL
calls, `@Serializable` classes, HTML strings and templates); `@` offers actions with snippets; `data-`
offers attributes; `__` offers modifiers and their values; in the options object of a backend
action, `@get('/x', {|})`, the options not yet written; after `data-on:`, Datastar's own events
(`datastar-fetch`, `datastar-signal-patch`) and the DOM's, in any file. Inside an HTML string in
Kotlin the HTML completions apply. A value is read as the browser reads it, so all of this works
over several lines and in single quotes, and in a Kotlin string a `\$` or `${'$'}` before a signal
stays as written.

**Signal navigation**: Go to Definition (F12, or ⌘+click on macOS and Ctrl+click elsewhere) on
`$count` goes to every place the workspace defines it: a `data-signals` on the page, a
`patchSignals` in a handler, a property of a signals class. Find All References (⇧F12) lists those
places and every expression that reads the signal, in markup and Kotlin strings alike. Rename
Symbol (F2) rewrites them all, each as its place needs: renaming `itemCount` to `basketCount`
writes the key `data-signals:basket-count`, since the browser lowercases a key. A place that
cannot hold the new name stops the rename with a message before anything is written: a key whose
`__case` gives no spelling of it back, a key of an object asked to move under another object, a
property of a signals class, which Kotlin's rename renames. In `$user.name.length` each part is
its own signal, so the caret on `user` renames `user` and on `name` renames `user.name`.

**Signature help** (⇧⌘Space on macOS, Ctrl+Shift+Space elsewhere, and on typing `(` or `,`) inside
an action call: `@get(uri, options?)`, with the argument the caret is on. In Kotlin the help starts
once the DSL call's parenthesis is closed, as the IDE closes it for you.

**Hover**: documentation for every attribute, modifier, action and option of a backend action,
and for the DSL functions, in HTML files, templates and HTML strings in Kotlin alike.

**Syntax highlighting** of Datastar expressions and HTML inside the DSL strings and in
free-standing HTML strings, and of Datastar's attributes in HTML, the catalog's and no other
`data-*` (plugin, key, modifiers and modifier arguments each get their own scope, and the value
of an attribute that takes an expression is highlighted as a Datastar expression). Most themes color only
a few of these scopes, so run `Streamlord: Apply recommended colors` once to write a palette
into your user settings; `Streamlord: Remove recommended colors` takes it out again.

**Stream Inspector** (`Streamlord: Open Stream Inspector`): connect to a running endpoint with
a method, signals and headers; every SSE event arrives decoded, and the signal store is kept
exactly as the client would keep it.
It asks as Datastar's client does, with the same `Accept`, and reads what the client reads: a
200 that is not HTML, JSON or JavaScript is decoded as a stream, whatever its `Content-Type`. Any
other response is shown with its `datastar-*` headers and up to 1 MiB of its body; a
`text/event-stream` response with any status but 200 is not read, as the client reads none.

- **Saved requests** live in `.streamlord/inspector.json` in the workspace, the same file the
  IntelliJ plugin
  uses (commit it to share with the team, or ignore it). Save one with **Save**, and pick it
  again from the Request list, which also holds the recent ones and says how many of each it
  has. **Delete** removes the selected one. You can write the file by hand as well:

  ```json
  {
    "version": 1,
    "requests": [
      {
        "name": "feed",
        "url": "{{baseUrl}}/api/feed/{{partsnummer}}",
        "method": "POST",
        "signals": { "search": "ash" },
        "headers": { "X-Id": "7" }
      }
    ]
  }
  ```

  `name` and `url` are needed, and a name is held once, whatever its case. `method` is `GET`
  when left out. `signals` is an object or JSON text, and `headers` an object or `Name: value`
  lines; text is the form to use for `{{signals}}` and `{{headers}}`. A header value that is
  a number, such as `1.0`, is sent as it is written. The file is JSON, so it takes no comments.
  With the file open in an editor, the Request list follows it as you type, saved or not.

  **Save** and **Delete** change the one entry and leave the rest of the file as you wrote it:
  the other entries, their layout, and what else the file holds. The entry that is saved is
  written in the layout above and keeps its members of your own, such as a `"note"`; `signals`
  stays an object where the new value is one, and `headers` an object on one line with text
  values where each line is a header of its own name. A request saved as it was read
  writes nothing. With the file open in an editor, the change goes into the editor's text as
  one edit you can undo, and what you typed there and did not save is kept. What the inspector cannot read is said in red under the Request list, and
  stays in the file: an entry without a `name` or a `url`, or a second entry of a name. A file
  that is not JSON, not an object with a `requests` list, or of another `version` than 1 is not
  read and not written over until it is fixed. An empty file is a file without requests.
- **Recent** requests are remembered automatically, and the last one is prefilled on open.
- **Variables** live in `.streamlord/env.json` in the workspace: three with a field of their own,
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
  `{{baseUrl}}` is where a server you started in a debug session said it listens, read from its
  output (`Tomcat started on port 9102 (http) with context path '/'`, or Ktor's
  `Responding at …`). With nothing running, it is the setting `streamlord.inspector.defaultUrl`,
  which is
  `http://localhost:8080` unless you change it.

  The Variables box in the panel lists each value, and says when `baseUrl` is the default or
  which debug session it came from.
  **Edit variables** opens the file, creating it with `baseUrl` filled in, and the panel
  follows the file as you type in it, saved or not. Any other key, or a value of the wrong kind, is reported by name
  and stops the request until it is fixed; the file also has a JSON schema, so the editor marks
  it as you type. The file is the place for local hosts, tokens and test values, so keep it out
  of version control. A server that cannot be reached is reported together with the `{{baseUrl}}` it was
  reached through; when the file's `baseUrl` is not where a running server said it started, a
  link sets the file's value to that one, changing nothing else. An error response is shown with
  the warnings and errors the server logged while answering it, such as Spring's
  `Required request parameter 'partsnummer' … is not present`. Only a debug session's output
  can be read by an extension, so a server started in a terminal is not seen.
- **Code lens** above every Ktor route (`route("/api") { get("/feed") }`) and Spring mapping
  (`@GetMapping("/feed")` under a class `@RequestMapping`): “Open in Stream Inspector” prefills
  the method and the whole URL the handler needs: each path parameter and each required
  `@RequestParam` as a `{{name}}`, and each required `@RequestHeader("X-Id")` as a line in the
  headers. A value that is not in `params` yet is reported with a link that adds it, empty, for
  you to fill in; the lens names the optional ones, such as `required = false`, which it leaves out.
- **Copy as curl** puts an equivalent `curl -N ...` command on the clipboard.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `streamlord.diagnostics.enabled` | `true` | Validate Kotlin strings and HTML. |
| `streamlord.diagnostics.html` | `true` | Also validate markup files. |
| `streamlord.diagnostics.unknownSignals` | `true` | Warn on a `$name` no file in the workspace defines. |
| `streamlord.languages` | html, jte, kte, ftl, velocity, mustache, pebble, twig, … | Language ids that get the HTML side. Reload after changing. |
| `streamlord.attributePrefix` | `data-` | `data-star-` when you load the aliased bundle. |
| `streamlord.inspector.defaultUrl` | `http://localhost:8080/` | `{{baseUrl}}` unless `.streamlord/env.json` sets `baseUrl`. |
| `streamlord.inspector.requestsFile` | `.streamlord/inspector.json` | Workspace-relative file holding the saved inspector requests, written with `/`, and not `.streamlord/env.json`. A path that is refused says why in the inspector, which then shows the default file. A file elsewhere gets no JSON schema. |
| `streamlord.inspector.codeLens` | `true` | Show “Open in Stream Inspector” above Ktor and Spring routes. |

## Datastar Pro

The extension knows the publicly documented names of Pro attributes and actions so it can
complete and validate them. It contains no Pro code and cannot make them work: that takes the
Pro bundle you license and load yourself.

## Development

Node 24 and JDK 21. The analysis is Kotlin, the same `streamlord-analysis` the IntelliJ plugin
runs: the Gradle project in `analysis-js` compiles it to JavaScript with its TypeScript types, and
`src/analysis.ts` is the one module the extension reaches it through.

```
npm ci
npm run analysis   # builds the analysis with ../../gradlew; the three below run it first, and it does nothing when no Kotlin has changed
npm test           # unit tests: the port to the analysis, providers (with a vscode mock), grammars (real TextMate engine)
npm run typecheck
npm run build      # generates html-customdata.json and snippets/html.json from the catalog, then dist/extension.js
npm run test:live  # the Stream Inspector client against the demo service (STREAMLORD_LIVE_URL, default http://localhost:8080)
npm run package    # streamlord-<version>.vsix
```

A rule of the analysis is changed in Kotlin, under `streamlord-analysis` or `streamlord-core` at
the repository root, and tested there: `./gradlew build` runs those tests on the JVM and on Node.
What the extension may call is `Port.kt` and `InspectorPort.kt` in `analysis-js`. `npm run watch`
builds the Kotlin once and then watches the TypeScript alone: after a Kotlin change, `npm run
analysis` in a second terminal, and the watch bundles what it wrote.

`test:live` needs the repository's demo running: `./gradlew :demo:run` from the repository root,
then `npm run test:live` here.

Press F5 in the `streamlord.code-workspace` to launch an Extension Development Host.

### What the real editor shows

`npm run real` builds the extension, starts the VS Code installed on this Mac with it, opens every
file of `test/fixtures/real` and writes down what the editor answers: diagnostics with their
links, each quick fix and the file after it, and at every offset of every file the hover, the
signature help, the definitions, the references and the rename with its edits; completions at the
places `test/real/marks.json` names, and at those it marks `"actions": true` the lightbulb at the
caret instead; code lenses, document links, colors and the registered commands. The answers are the snapshot in `test/real/snapshot`, one record a line.

```
npm run real              # record the snapshot again
npm run real -- --check   # run and compare: fails with the lines that differ
```

It is a local tool and no CI job: it needs VS Code in `/Applications` (or `STREAMLORD_REAL_CODE`
set to its `Contents/MacOS/Code`), and a window opens while it runs and closes by itself. The
profile is a fresh one under `/tmp/streamlord-real` (`STREAMLORD_REAL_TMP`, a short path), so your
own settings and extensions take no part. Run the check before and after a change to the
extension's internals; record again only when a difference is one you meant.

A file under `test/real/outside` lies in no folder of the workspace: the editor is shown it and
never finds it by itself, as any file in a window without a folder. A code lens is recorded with
the request it fills the inspector with.

The workspace holds the three ways of writing markup: HTML and template files, HTML in Kotlin
strings, and the kotlinx.html DSL. The files under `drawn` are cases of `test/fixtures/corpus`,
each as a file of its own (`drawn/origin.json` says which), and those under `aliased` are read with
the prefix `data-star-`. `test/real/languages` declares Kotlin and the template languages, which
other extensions bring: without one that declares `kotlin` the extension does nothing in a `.kt`
file. VS Code hands no extension the TextMate scopes it colours by, so the scopes in the snapshot
are read with the same engine (`vscode-textmate`), VS Code's own HTML grammar and the extension's
grammars; Kotlin, which VS Code ships no grammar for, is read with `test/fixtures/host-kotlin.json`.

### Publishing

The extension is published to the VS Code Marketplace from CI only, never from a developer
machine. Bump `version` in `package.json`, commit, then push a tag `vscode-v<version>`. The
`publish-vscode` job checks that the tag matches, builds, tests, and runs `vsce publish` with
the `VSCE_PAT` repository secret (an Azure DevOps personal access token with the Marketplace
*Manage* scope for the `MarkusAugust` publisher). Which branch the bump goes on, and when the
tag may be pushed, is in [the repository README](https://github.com/MarkusAugust/streamlord#building-and-releasing).

No `.vsix` is ever committed; `npm run package` only builds one locally for installing by hand. The
attribute and action catalog lives in `../../catalog/` and is verified against the Kotlin DSL
by the SDK's own tests.
