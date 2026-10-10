---
title: "Components and plain JavaScript"
description: "Custom elements, third-party widgets and your own scripts next to a page the server morphs."
---

Datastar owns the attributes it reads and the elements it patches. Everything else on the page,
a custom element, a chart library, a script you wrote, lives next to it. The rules on this page
keep the two from stepping on each other. What they say about the client was checked against
the Datastar client's source.

## Attributes down, events up

A custom element does not need to know Datastar exists. It takes its inputs as attributes and
reports what happened as events. The page connects the two:

```kotlin sample=declarations
@org.intellij.lang.annotations.Language("HTML")
val colorField: String =
    $$"""
    <div data-signals="{color: '#ff0000'}">
        <color-picker data-attr:value="$color"
                      data-on:color-input="$color = evt.detail.value"></color-picker>
    </div>
    """.trimIndent()
```

`data-attr:value` writes the signal into the attribute whenever it changes, so the element reads
it in `attributeChangedCallback`, provided `value` is in its `observedAttributes`.
`data-on:color-input` listens on the element itself, which means the element's event has to
start there or bubble to it: dispatch with `bubbles: true` when it starts on an inner node.

The element stays reusable because the page decides what an event means. One page posts the
colour, another only shows it, and the element is the same in both.

## Scripts reach signals through events

Your own JavaScript does not need a handle on the signal store. Dispatch an event and let an
attribute put the value where it belongs:

```kotlin sample=html
div {
    dataSignals("position" to 0)
    dataOn("player-progress", $$"$position = evt.detail.seconds") { window = true }
}
```

```js
window.dispatchEvent(new CustomEvent("player-progress", { detail: { seconds: 42 } }))
```

`window = true` writes `__window`, which listens where the script dispatched. Mount the listener
on an element no patch replaces, a wrapper outside the morphed region: a listener leaves with
its element, and one on a patched element is gone the moment a patch removes it.

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

When the widget only adds an attribute to its host, such as `open` on a `<details>`,
`dataPreserveAttr("open")` keeps that attribute through a morph and lets the rest update. The
client reads it from the patch, so it too belongs in every render. It works on whole attributes:
preserving `class` freezes the entire class list, the classes the server changes included.

An element without `dataIgnoreMorph` should assume any attribute can change under it. Read
attributes in `attributeChangedCallback` rather than once in `connectedCallback`, and set again
whatever the element added for itself, because the server's markup does not carry it.

## Showing a change before the server answers

The `datastar-fetch` event reports every request's life: `started`, `finished`, `error`,
`retrying` and `retries-failed`. It fires on `document`, so `dataOnFetch` on any element hears
every request on the page, and it carries the element that ran the action as `evt.detail.el`.
That is enough to show a change at once and take it back when the request fails:

```kotlin sample=html
val task = 7
val done = false
ul {
    li {
        id = "task-${task}"
        dataSignals("_done${task}" to done)
        dataClass("done", signal("_done${task}"))
        button {
            dataOnClick(statements(toggle("_done${task}"), post("/tasks/${task}/toggle")))
            +"Done"
        }
        dataOnFetch(
            "['${FetchEventType.ERROR}', '${FetchEventType.RETRIES_FAILED}']" +
                ".includes(evt.detail.type) " +
                "&& evt.detail.el.closest('#task-${task}') && (${set("_done${task}", done)})",
        )
    }
}
```

`error` means the server answered with a 4xx or 5xx; a lost connection retries and ends in
`retries-failed`, so the rollback listens for both. With the default `retry: 'auto'` that end
comes after ten attempts with backoff, about three minutes, so an offline reader sees the change
stand that long. The rollback sets the stored value rather than toggling back, which keeps it
right when a retry mode fires several failures for one click. The filter on `evt.detail.el` keeps
other requests on the page from undoing this one.

Signals are global to the page, so each item needs its own name, built from its id, and its own
seed, the stored value. The underscore keeps the signal out of the request. When the command
succeeds, the server's patch renders the item with the new stored value, and the client applies
`data-signals` again because the attribute's value has changed. A command the server declines
answers with a 4xx, so the rollback runs; a 2xx carrying the old value changes no attribute and
leaves the optimistic state standing.

Keep optimistic state small and local: the server's answer is the one that stays.
