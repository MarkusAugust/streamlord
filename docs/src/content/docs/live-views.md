---
title: "Live views"
description: "A page whose state the server owns, kept up to date over a stream, and the URL that goes with it."
---

A live view is a page whose state the server owns. The browser asks for changes, the server
decides what they mean and patches the page, and the page holds no rules of its own about what a
filter, a search or a selection does. This page collects what that takes beyond the stream
itself. The [live demo](/live/) is a running example.

## The address bar belongs to the server

When the server owns a filter or a search, it also owns the URL that names it. Build that URL in
one place, from the same state the page is rendered from, and send it with the patch. One route
answers both ways: a plain request, from a bookmark or a reload, gets the whole page, and a
Datastar request gets the patch and the URL.

```kotlin sample=declarations
fun searchUrl(query: String): String = "/search?q=" + java.net.URLEncoder.encode(query, Charsets.UTF_8)

fun searchResults(query: String): String =
    interpolate("""<ul id="results"><li>Results for %s</li></ul>""", query)

fun searchPage(results: String): String = "<!doctype html><html><body>$results</body></html>"
```

```kotlin sample=ktor-routing
get("/search") {
    val query = call.request.queryParameters["q"].orEmpty()

    if (!call.isDatastarRequest) {
        call.respondText(searchPage(searchResults(query)), ContentType.Text.Html)
        return@get
    }
    call.respondDatastar {
        patchElements(searchResults(query))
        replaceUrl(searchUrl(query))
    }
}
```

`searchResults` goes through `interpolate`, because the query is the reader's own text and lands
in HTML; [Strings](/strings/) has why. `searchUrl` is the one place the URL is built, so the
browser's code never learns how, and there is nothing to keep in step.

`replaceUrl` swaps the current history entry, so a search that changes on every keystroke does
not fill the back button. `pushUrl` adds an entry, for a step the reader should be able to go back
from. Both quote the URL as a JavaScript string. The browser refuses one of another origin, so
pass a path or a URL on the page's own origin.

After `pushUrl`, Back changes the URL without reloading, and the page hears a `popstate` on
`window`. Ask the server for the URL the reader returned to:

```kotlin sample=html
div {
    dataOn("popstate", "@get(location.pathname + location.search)") { window = true }
}
```

That `@get` is a Datastar request, so the route above answers it with the patch. Its answer must
not push the URL again: `replaceUrl` it or leave it alone, or Back only ever leads forward.
Datastar Pro's `dataReplaceUrl` does the replacing from the client instead, from an expression on
the page.
