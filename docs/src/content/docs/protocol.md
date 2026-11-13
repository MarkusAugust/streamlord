---
title: "The protocol"
description: "Since 1.0, Datastar knows two SSE events. Everything else is built on those two."
---

> *You were told there were five rites. Whoever told you that read a grimoire from before the
> Ashfall.*

Older documentation speaks of `merge-fragments`, `remove-fragments`, `merge-signals` and
`remove-signals`. Those spells died with 0.x. Since 1.0, Datastar has **two** events.

| Event | What it does | In Streamlord |
|---|---|---|
| `datastar-patch-elements` | Patch complete HTML elements into the DOM | `PatchElements` |
| `datastar-patch-signals` | Merge-patch the signal store, by RFC 7386 | `PatchSignals` |

Removal is not a third event: it is a `PatchElements` with mode `remove`. A signal does not get
deleted either. It is patched to `null`, and RFC 7386 says a null member is a removal.

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

The blank line ends the frame. Those bytes are not typed out here. They are written by
Streamlord's own encoder during the build, from the event above. Single-line fields are guarded so that a selector carrying a
newline cannot forge a second `data:` line. See [Security](/security/).

## Removing elements

`remove` takes the elements the selector matches out of the page, and needs no markup:

```kotlin sample=stream
removeElements("#quest-7")
```

On its own, as a `Flow` of events carries it, that is `PatchElements.remove`, short for
`PatchElements(selector = "#quest-7", mode = ElementPatchMode.REMOVE)`:

```kotlin wire=remove-mode
PatchElements.remove("#quest-7")
```

```wire=remove-mode
event: datastar-patch-elements
data: selector #quest-7
data: mode remove
```

## View transitions

`useViewTransition = true` has the client run the patch inside the browser's
[view transition](https://developer.mozilla.org/en-US/docs/Web/API/View_Transition_API), so the
page cross-fades from the old state to the new one, or animates as your
`::view-transition-*` CSS says. `viewTransitionSelector` runs it on one element instead of the
whole document; an element the selector does not find leaves it on the document. A browser
without view transitions patches at once. A removal takes both too:

```kotlin sample=stream
patchElements(
    """<ul id="quests"><li>Ashfall</li><li>The Gate</li></ul>""",
    useViewTransition = true,
    viewTransitionSelector = "#board",
)
removeElements("#quest-7", useViewTransition = true)
```

```kotlin wire=view-transition
PatchElements(
    """<ul id="quests"><li>Ashfall</li></ul>""",
    useViewTransition = true,
    viewTransitionSelector = "#board",
)
```

```wire=view-transition
event: datastar-patch-elements
data: useViewTransition true
data: viewTransitionSelector #board
data: elements <ul id="quests"><li>Ashfall</li></ul>
```

The client reads `viewTransitionSelector` only when it starts a view transition, so a selector
without `useViewTransition = true` is refused rather than sent for nothing.

## SVG and MathML

The browser parses `<circle>` as an SVG element only inside an `<svg>`. A patch of shapes on their
own says so with `namespace`, and the client parses them inside an `<svg>`, or a `<math>` for
MathML, before it patches them in:

```kotlin wire=svg-namespace
PatchElements(
    """<circle id="sun" cx="50" cy="50" r="20"></circle>""",
    namespace = ElementNamespace.SVG,
)
```

```wire=svg-namespace
event: datastar-patch-elements
data: namespace svg
data: elements <circle id="sun" cx="50" cy="50" r="20"></circle>
```

```kotlin sample=stream
patchElements(
    "<mfrac><mn>1</mn><mn>3</mn></mfrac>",
    selector = "#odds",
    mode = ElementPatchMode.INNER,
    namespace = ElementNamespace.MATHML,
)
```

A patch that holds its own `<svg>` or `<math>` needs no namespace. Streamlord refuses the two
mistakes the client would swallow: an SVG or MathML element such as `<circle>` in the default
`html` namespace, which the browser makes an HTML element that draws nothing, and an HTML element
such as `<div>` in the `svg` or `mathml` namespace, which closes the `<svg>` the client parses in,
so it and all after it are dropped. HTML inside SVG goes in a `<foreignObject>`.

## How it is verified

The encoder is checked against the **official Datastar SDK golden files** on every build, and
the Ktor adapter runs the official conformance server in-process during its tests. When the
specification and this documentation disagree, the specification wins and this page is the bug.
