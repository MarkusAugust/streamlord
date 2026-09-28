---
title: "The protocol"
description: "Datastar 1.0 knows two SSE events. Everything else is built on those two."
---

> *You were told there were five rites. Whoever told you that read a grimoire from before the
> Ashfall.*

Older documentation speaks of `merge-fragments`, `remove-fragments`, `merge-signals` and
`remove-signals`. Those spells died with 0.x. Datastar 1.0 has **two** events.

| Event | What it does | In Streamlord |
|---|---|---|
| `datastar-patch-elements` | Patch complete HTML elements into the DOM | `PatchElements` |
| `datastar-patch-signals` | Merge-patch the signal store, by RFC 7386 | `PatchSignals` |

Removal is not a third event: it is a `PatchElements` with mode `remove`. A signal does not get
deleted either — it is patched to `null`, and RFC 7386 says a null member is a removal.

`ExecuteScript` looks like a third event and is not. The SDK specification defines it as sugar on
top of `patch-elements`: a `<script>` appended to `body`, which removes itself once it has run.
Streamlord models it as its own type because you will want it, and encodes it as what it is.

## The eight patch modes

`outer` (the default, a morph), `inner` (also a morph), `replace`, `prepend`, `append`, `before`,
`after`, `remove`.

Every mode except `outer` and `replace` **requires a selector**, because there is nothing in the
element itself to say where "before" is. Streamlord will not build an event the client would
reject:

```kotlin sample=statements
// Refused at construction, not in a browser console.
PatchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
```

Give it a selector and it is fine:

```kotlin wire=append-mode
PatchElements("<li>x</li>", selector = "#list", mode = ElementPatchMode.APPEND)
```

## What goes on the wire

That last event encodes as:

```wire=append-mode
event: datastar-patch-elements
data: selector #list
data: mode append
data: elements <li>x</li>
```

The blank line ends the frame. Those bytes are not typed out here — they are written by
Streamlord's own encoder during the build, from the event above. Single-line fields are guarded so that a selector carrying a
newline cannot forge a second `data:` line — see [Security](/security/).

## How it is verified

The encoder is checked against the **official Datastar SDK golden files** on every build, and
the Ktor adapter runs the official conformance server in-process during its tests. When the
specification and this documentation disagree, the specification wins and this page is the bug.
