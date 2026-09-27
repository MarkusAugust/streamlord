# Streamlord for VS Code

> *The forge is one thing. The eye that sees the flaw before the blade is quenched is another.
> I have both, and I lend them to you for nothing. Ask yourself why.*
>
> — Sarn the Faceless

Editor support for the [Streamlord](https://github.com/MarkusAugust/streamlord) Kotlin SDK and
the Datastar 1.0.4 protocol.

## What it does

**Diagnostics** in Kotlin strings, whether passed to a Streamlord call or free-standing (a
function that returns HTML, a `val` holding a fragment), and in `.html` and template files:

- Kotlin interpolation of signals: `"$count++"` becomes `"++"` at runtime. Flagged as an error
  with the fix spelled out. In HTML strings, `$name` inside a `data-*` expression is the same
  error; `$name` in text is a hint, since there it is usually meant.
- Datastar expression syntax, checked with a real JavaScript parser, with the error at the
  right column.
- Unknown actions (`@Post`), Pro actions and Pro attributes (hint: they need the Pro bundle).
- Markup in `patchElements(...)`: unclosed or stray tags, text outside elements, missing `id`
  on top-level elements when no selector is given, a mode that needs a selector.
- `data-*` attributes: unknown names with "did you mean", unknown modifiers, wrong modifier
  arguments (`__debounce` without a duration, `__threshold.150`), missing or unexpected keys.
- Casing: a capital letter in a key (`data-signals:fooBar`), which the browser lowercases so
  the signal becomes `$foobar`, and a hyphen after a signal in an expression (`$foo-bar`,
  `$count-1`), which Datastar swallows into one signal name. Both come with the fix: the
  kebab-case key (plus `__case.camel` for `data-on` and `data-class`), and the camelCase signal
  or the spaced subtraction. In the DSL the SDK writes a camelCase key correctly
  on its own, and a hint on the call shows what goes on the wire.

A string counts as HTML when it opens with a tag, a comment or a doctype, or when it carries
IntelliJ's injection marker: `@Language("HTML")` on the function or property, or a
`// language=HTML` comment just before it. Multi-dollar literals (`$$"""..."""`, Kotlin 2.2+)
are read as Kotlin reads them: a single `$` is text, so `$count` there is a signal and draws
no interpolation warning. Free-standing strings get the attribute checks of a
template file; the id and completeness rules apply only where the string is handed to
`patchElements` and its kin, because only there is the patch mode known.

**Quick fixes** on the lightbulb: `$count` becomes `increment("count")`, `signal("count")`,
`toggle("open")` or an escaped `${'$'}count`; `__debunce` becomes `__debounce`; `@Post` becomes
`@post`; `data-signal` becomes `data-signals`; a missing duration, `id` or `selector` is added.
Every diagnostic links to the matching page of the Datastar reference.

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
offers attributes; `__` offers modifiers and their values. Inside an HTML string in Kotlin
the HTML completions apply.

**Hover**: documentation for every attribute, modifier and action, and for the DSL functions,
in HTML files, templates and HTML strings in Kotlin alike.

**Syntax highlighting** of Datastar expressions and HTML inside the DSL strings and in
free-standing HTML strings, and of `data-*` attributes in HTML (plugin, key, modifiers and
modifier arguments each get their own scope, and the attribute value is highlighted as a
Datastar expression). Most themes color only
a few of these scopes, so run `Streamlord: Apply recommended colors` once to write a palette
into your user settings; `Streamlord: Remove recommended colors` takes it out again.

**Stream Inspector** (`Streamlord: Open Stream Inspector`): connect to a running endpoint with
a method, signals and headers; every SSE event arrives decoded, and the signal store is kept
exactly as the client would keep it. Non-SSE Datastar responses are shown with their
`datastar-*` headers.

- **Saved requests** live in `.streamlord/inspector.json` in the workspace (commit it to share
  with the team, or ignore it). Save, load and delete from the panel, or edit the file; it has
  a JSON schema.
- **Recent** requests are remembered automatically, and the last one is prefilled on open.
- **Variables**: `{{baseUrl}}` and any other `{{name}}` in URL, headers and signals. Values
  come from `streamlord.inspector.variables` in settings, overridden by
  `.streamlord/env.json` in the workspace, which is the place for tokens and should be
  git-ignored. `{{baseUrl}}` defaults to `streamlord.inspector.defaultUrl`.
- **Code lens** above every Ktor route (`route("/api") { get("/feed") }`) and Spring mapping
  (`@GetMapping("/feed")` under a class `@RequestMapping`): “Open in Stream Inspector” prefills
  method and `{{baseUrl}}` + path, asking for `{id}` parameters.
- **Copy as curl** puts an equivalent `curl -N ...` command on the clipboard.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `streamlord.diagnostics.enabled` | `true` | Validate Kotlin strings and HTML. |
| `streamlord.diagnostics.html` | `true` | Also validate markup files. |
| `streamlord.languages` | html, jte, kte, ftl, velocity, mustache, pebble, twig, … | Language ids that get the HTML side. Reload after changing. |
| `streamlord.attributePrefix` | `data-` | `data-star-` when you load the aliased bundle. |
| `streamlord.inspector.defaultUrl` | `http://localhost:8080/` | Prefilled in the inspector. |

## Datastar Pro

The extension knows the publicly documented names of Pro attributes and actions so it can
complete and validate them. It contains no Pro code and cannot make them work: that takes the
Pro bundle you license and load yourself.

## Development

```
npm ci
npm test           # unit tests: analysis, providers (with a vscode mock), grammars (real TextMate engine)
npm run test:live  # the Stream Inspector client against a running Datastar server (STREAMLORD_LIVE_URL, default http://localhost:8080/api)
npm run build      # generates html-customdata.json and snippets/html.json from the catalog, then dist/extension.js
npm run package    # streamlord-<version>.vsix
```

Press F5 in the `streamlord.code-workspace` to launch an Extension Development Host.

### Publishing

The extension is published to the VS Code Marketplace from CI only, never from a developer
machine. Bump `version` in `package.json`, commit, then push a tag `vscode-v<version>`. The
`publish-vscode` job checks that the tag matches, builds, tests, and runs `vsce publish` with
the `VSCE_PAT` repository secret (an Azure DevOps personal access token with the Marketplace
*Manage* scope for the `MarkusAugust` publisher).

No `.vsix` is ever committed; `npm run package` only builds one locally for installing by hand. The
attribute and action catalog lives in `../../catalog/` and is verified against the Kotlin DSL
by the SDK's own tests.
