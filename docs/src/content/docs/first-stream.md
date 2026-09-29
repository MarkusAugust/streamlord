---
title: "Your first stream"
description: "From an empty route to a patched element, and the bytes in between."
---

The smallest useful thing Streamlord does: a request arrives, the server sends HTML for part of
the page, and the browser puts it where you said.

## The page

Datastar needs to be on the page, and something needs to ask for the stream. This is plain HTML;
no build step, no bundle of yours.

```html
<script type="module" src="/static/datastar.js"></script>

<button data-on-click="@get('/feed')">Load the feed</button>
<ul id="feed"></ul>
```

## The route

```kotlin sample=ktor-routing
get("/feed") {
    call.respondDatastar {
        patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
            li { +"A new head hangs on the wall" }
        }
    }
}
```

`respondDatastar` opens a Server-Sent Events response, runs your block, and closes when the block
returns. Everything you call inside it writes one frame.

## What went over the wire

That route builds one event:

```kotlin wire=first-feed
PatchElements(
    elements = "<li>A new head hangs on the wall</li>",
    selector = "#feed",
    mode = ElementPatchMode.APPEND,
)
```

and this is exactly what the encoder makes of it:

```wire=first-feed
event: datastar-patch-elements
data: selector #feed
data: mode append
data: elements <li>A new head hangs on the wall</li>
```

That is the whole exchange. The browser appends the `<li>` to `#feed` and nothing else on the
page is touched. You will rarely construct a `PatchElements` yourself, since `patchElements { }` does
it for you, but it is the thing the protocol carries, and worth seeing once.

## Adding state

Elements are half of Datastar. The other half is signals: a small reactive store in the browser
that your server can patch in the same stream.

```kotlin sample=ktor-routing
get("/feed") {
    call.respondDatastar {
        patchElements(selector = "#feed", mode = ElementPatchMode.APPEND) {
            li { +"A new head hangs on the wall" }
        }
        patchSignals("heads" to 13)
    }
}
```

Now `<span data-text="$heads"></span>` anywhere on the page reads 13, and a button with
`data-show="$heads > 0"` appears. You never wrote a line of JavaScript, and the server never
learned what the page looks like.

## Where to go next

The stream above closes immediately. It does not have to: hand `respondDatastar` a `Flow` and it
stays open for as long as the flow produces, which is how you build a counter, a progress bar or
a live log. That, the plugin, and reading signals back are on the [Ktor](/ktor/) page, or
[Spring WebMVC](/spring-webmvc/) if that is your realm.
