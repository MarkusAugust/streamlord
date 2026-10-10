---
title: "Components and plain JavaScript"
description: "Custom elements, third-party widgets and your own scripts next to a page the server morphs."
---

Datastar owns the attributes it reads and the elements it patches. Everything else on the page,
a custom element, a chart library, a script you wrote, lives next to it. The rules on this page
keep the two from stepping on each other.

## Attributes down, events up

A custom element does not need to know Datastar exists. It takes its inputs as attributes and
reports what happened as events. The page connects the two:

```kotlin sample=declarations
@org.intellij.lang.annotations.Language("HTML")
val colorField: String =
    $$"""
    <div data-signals="{color: '#ff0000'}">
        <color-picker data-attr:value="$color" data-on:color-input="$color = evt.detail.value"></color-picker>
    </div>
    """
```

`data-attr:value` writes the signal into the attribute whenever it changes, so the element reads
it in `attributeChangedCallback` like any other. `data-on:color-input` listens on the element
itself, which means the element's event has to start there or bubble to it: dispatch with
`bubbles: true` when it starts on an inner node.

The element stays reusable because the page decides what an event means. One page posts the
colour, another only shows it, and the element is the same in both.

## Scripts cannot write signals; events can

Your own JavaScript has no handle on the signal store. It does not need one. Dispatch an event
and let an attribute put the value where it belongs:

```kotlin sample=html
div {
    dataSignals("position" to 0)
    dataOn("player-progress", "\$position = evt.detail.seconds") { window = true }
}
```

```js
window.dispatchEvent(new CustomEvent("player-progress", { detail: { seconds: 42 } }))
```

`window = true` writes `__window`, which listens where the script dispatched. Mount the listener
on an element no patch replaces, a wrapper outside the morphed region, so it is attached once.

## Surviving a morph

A morph keeps an element whose `id` matches and updates its attributes and children to what the
server sent. For a widget that builds its own DOM inside the element, that is the wrong thing:
the server never sent the widget's DOM, so the morph removes it.

`dataIgnoreMorph()` turns the morph off for the element and everything inside it. The client
skips an element only when **both** the one on the page and the one in the patch carry the
attribute, so write it every time you render the element, not only the first time:

```kotlin sample=html
div {
    id = "chart"
    dataIgnoreMorph()
}
```

Leave it off once, and the next patch morphs the widget away.

When the widget only adds an attribute to its host (`open` on a `<details>`, a class it toggles),
`dataPreserveAttr("open")` keeps that attribute through a morph and lets the rest update.

An element without `dataIgnoreMorph` should assume any attribute can change under it. Read
attributes in `attributeChangedCallback` rather than once in `connectedCallback`, and set again
whatever the element added for itself, because the server's markup does not carry it.

## Showing a change before the server answers

The `datastar-fetch` event reports every request's life: `started`, `finished`, `error`,
`retrying` and `retries-failed`. It fires on `document`, carries the element that ran the action
as `evt.detail.el`, and `dataOnFetch` listens for it. That is enough to show a change at once and
take it back when the request fails:

```kotlin sample=html
ul {
    li {
        id = "task-7"
        dataSignals("_done" to false)
        dataClass("done", signal("_done"))
        button {
            dataOnClick(statements(toggle("_done"), post("/tasks/7/toggle")))
            +"Done"
        }
        dataOnFetch(
            "evt.detail.type === 'error' && evt.detail.el.closest('#task-7') && (\$_done = !\$_done)",
        )
    }
}
```

The underscore keeps `_done` out of the request, and the server's own patch replaces the item
when the command succeeds. Keep optimistic state small and local: the server's answer is the one
that stays.
