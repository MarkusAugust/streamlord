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
  spelled out. In HTML strings, `$name` inside a `data-*` expression is a warning; `$name` in text
  is a weak warning, since there it is usually meant.
- Datastar expression syntax, checked with a real JavaScript parser, with the error at the right
  column.
- Unknown actions (`@Post`), Pro actions and Pro attributes (they need the Pro bundle).
- Markup in `patchElements(...)`: unclosed or stray tags, text outside elements, a missing `id`
  on a top-level element when no selector is given, a mode that needs a selector.
- `data-*` attributes: unknown names with "did you mean", unknown modifiers, wrong modifier
  arguments (`__debounce` without a duration, `__threshold.150`), missing or unexpected keys.
- Casing: a capital letter in a key (`data-signals:fooBar`), which the browser lowercases so the
  signal becomes `$foobar`, and a hyphen after a signal in an expression (`$foo-bar`,
  `$count-1`), which Datastar swallows into one signal name. Both come with the fix. In the DSL
  the SDK writes a camelCase key correctly on its own, and a weak warning on the call shows what
  goes on the wire.

A string counts as HTML when it opens with a tag, a comment or a doctype, or when it carries
`@Language("HTML")` or a `// language=HTML` comment. Multi-dollar literals (`$$"""..."""`,
Kotlin 2.2+) are read as Kotlin reads them: a single `$` is text, so `$count` there is a signal.
A call the Kotlin plugin resolves to something other than Streamlord is left alone.

**Quick fixes** on Alt+Enter: `$count` becomes `increment("count")`, `signal("count")`,
`toggle("open")`, a `$$` literal or an escaped `${'$'}count`; `__debunce` becomes `__debounce`;
`@Post` becomes `@post`; `data-signal` becomes `data-signals`; a missing duration, `id` or
`selector` is added. Every message links to the matching page of the Datastar reference.

**Completion**: `$` offers every signal name declared anywhere in the project (Kotlin DSL calls,
`@Serializable` classes, HTML strings and templates), this file's first; `@` offers actions;
`data-` offers attributes; `__` offers modifiers and their values; `#` and `.` in any `selector`
argument offer the ids and classes declared in the project. When the official Datastar plugin is
installed, the attribute names in HTML files are left to it, so nothing is listed twice.

**Documentation** on hover (Ctrl+Q) for every attribute, modifier and action, in HTML files,
templates and HTML strings in Kotlin alike, and for the DSL functions where the Kotlin plugin
has no KDoc to show.

**Highlighting** of Datastar expressions inside DSL strings and HTML strings, and of `data-*`
attributes in HTML: prefix, plugin, key, modifiers and modifier arguments each get their own
colour, and the attribute value is coloured as a Datastar expression. Only the Datastar tokens
are coloured; your theme keeps everything else. Settings | Editor | Color Scheme | Streamlord.

**HTML injection** into Kotlin strings that open with a tag but carry no `@Language("HTML")`, so
tags complete and close as in an HTML file. Streamlord's own parameters carry the annotation
already.

**Live templates** for Ktor and Spring routes and the DSL calls in Kotlin, for a function that
returns HTML as a `$$"""..."""` string (`htmlFunction`), and for every `data-*` attribute in
HTML (`data-on` Tab).

**Template languages**: the HTML side runs wherever IntelliJ shows an HTML tree: `.html`
(Thymeleaf), and with their plugins FreeMarker, Velocity, JTE, Mustache, Pebble and the rest.
The engine's own syntax (`${...}`, `!{...}`, `{{...}}`, `@if`, `#if`, `<#if>`, `[#if]`) is left
alone: an attribute value that contains it cannot be judged before rendering.

**Stream Inspector** (the Streamlord tool window): connect to a running endpoint with a method,
signals and headers; every SSE event arrives decoded, and the signal store is kept exactly as
the client would keep it. Non-SSE Datastar responses are shown with their `datastar-*` headers.

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
  `{{baseUrl}}` is the Default base URL setting, which is `http://localhost:8080` unless you
  change it.

  The Variables box in the panel lists each value, and marks `baseUrl` when it is the default.
  **Edit variables** opens the file, creating it with `baseUrl` filled in, and the panel picks
  up the file as you save it. Any other key, or a value of the wrong kind, is reported by name
  and stops the request until it is fixed; the file also has a JSON schema, so the editor marks
  it as you type. The file is the place for local hosts, tokens and test values, so keep it out
  of version control. A server that cannot be reached is reported together with the `{{baseUrl}}` it was
  reached through.
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
| Leave attribute names to the Datastar plugin | on | No double listing in HTML files when the official plugin is installed. |
| Default base URL | `http://localhost:8080/` | `{{baseUrl}}` unless `.streamlord/env.json` sets `baseUrl`. |
| Saved requests file | `.streamlord/inspector.json` | Project-relative. |
| Gutter icons on routes | on | "Open in Stream Inspector" on Ktor and Spring routes. |

The inspections are under Settings | Editor | Inspections | Streamlord, one per family
(interpolation, expression, attribute, key casing, patch markup), each with its own severity.

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
`JETBRAINS_MARKETPLACE_TOKEN` repository secret (a Marketplace permanent token for the
`MarkusAugust` vendor); `JETBRAINS_CERTIFICATE_CHAIN`, `JETBRAINS_PRIVATE_KEY` and
`JETBRAINS_PRIVATE_KEY_PASSWORD` sign the archive when present.
