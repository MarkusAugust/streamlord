# Streamlord for VS Code

> *The forge is one thing. The eye that sees the flaw before the blade is quenched is another.*

Editor support for the [Streamlord](https://github.com/MarkusAugust/streamlord) Kotlin SDK and
the Datastar 1.0.4 protocol.

## What it does

**Diagnostics** in Kotlin strings passed to the Streamlord DSL, and in `.html` templates:

- Kotlin interpolation of signals: `"$count++"` becomes `"++"` at runtime. Flagged as an error
  with the fix spelled out.
- Datastar expression syntax, checked with a real JavaScript parser, with the error at the
  right column.
- Unknown actions (`@Post`), Pro actions and Pro attributes (hint: they need the Pro bundle).
- Markup in `patchElements(...)`: unclosed or stray tags, text outside elements, missing `id`
  on top-level elements when no selector is given, a mode that needs a selector.
- `data-*` attributes: unknown names with "did you mean", unknown modifiers, wrong modifier
  arguments (`__debounce` without a duration, `__threshold.150`), missing or unexpected keys.

**Completions**: `#` and `.` in any `selector` argument offer the ids and classes declared
anywhere in the workspace, this file first; `$` offers every signal name declared anywhere in the workspace (Kotlin DSL
calls, `@Serializable` classes, HTML templates); `@` offers actions with snippets; `data-`
offers attributes; `__` offers modifiers and their values.

**Hover**: documentation for every attribute, modifier and action, and for the DSL functions.

**Syntax highlighting** of Datastar expressions and HTML inside the DSL strings, and of
`data-*` attributes in HTML (plugin, key, modifiers and modifier arguments each get their own
scope, and the attribute value is highlighted as a Datastar expression). Most themes color only
a few of these scopes, so run `Streamlord: Apply recommended colors` once to write a palette
into your user settings; `Streamlord: Remove recommended colors` takes it out again.

**Stream Inspector** (`Streamlord: Open Stream Inspector`): connect to a running endpoint with
a method, signals and headers; every SSE event arrives decoded, and the signal store is kept
exactly as the client would keep it. Non-SSE Datastar responses are shown with their
`datastar-*` headers.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `streamlord.diagnostics.enabled` | `true` | Validate Kotlin strings and HTML. |
| `streamlord.diagnostics.html` | `true` | Also validate `.html` files. |
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
npm run build      # dist/extension.js
npm run package    # streamlord-<version>.vsix
```

Press F5 in VS Code with `editors/vscode` open to launch an Extension Development Host. The
attribute and action catalog lives in `../../catalog/` and is verified against the Kotlin DSL
by the SDK's own tests.
