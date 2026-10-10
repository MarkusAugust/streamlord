---
title: "Live views"
description: "A page the server keeps up to date over one open stream, and the URL that goes with it."
---

A live view is a page whose state the server owns. The page holds one stream open, commands
arrive on other requests, and every change goes down the stream that was already there. This page
collects what that takes beyond the stream itself.

## The address bar belongs to the server

When the server owns a filter, a search or a selection, it also owns the URL that names it. Build
that URL once, on the server, from the same state the page was rendered from, and send it with
the patch:

```kotlin sample=ktor-routing
get("/search") {
    val query = call.request.queryParameters["q"].orEmpty()

    call.respondDatastar {
        patchElements("""<ul id="results"><li>Results for $query</li></ul>""")
        replaceUrl("/search?q=${java.net.URLEncoder.encode(query, Charsets.UTF_8)}")
    }
}
```

`replaceUrl` swaps the current history entry, so a filter that changes on every keystroke does
not fill the back button. `pushUrl` adds an entry, for a step the reader should be able to go back
from. The browser's code never learns how a URL is put together, so there is one place that
builds it and nothing to keep in step.

Both quote the URL as a JavaScript string. The browser refuses one of another origin, so pass a
path or a URL on the page's own origin.

After `pushUrl`, Back changes the URL without reloading, and the page hears a `popstate` on
`window`. Ask the server to render the URL the reader returned to, from the route that rendered
it the first time:

```kotlin sample=html
div {
    dataOn("popstate", "@get(location.pathname + location.search)") { window = true }
}
```

The `/search` route above answers it as it answers any other request for that URL.
Datastar Pro's [`data-replace-url`](/datastar-pro/) does the replacing from the client instead,
from an expression on the page.
