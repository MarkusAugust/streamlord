---
title: "Live views"
description: "A page whose state the server owns, kept up to date over a stream, and the URL that goes with it."
---

A live view is a page whose state the server owns. The browser asks for changes, the server
decides what they mean and patches the page, and the page holds no rules of its own about what a
filter, a search or a selection does. This page collects what that takes beyond the stream
itself. The [live demo](/live/) is a running example.

## Commands go in, the view comes out

Split the two directions. A **command** is a short request that changes state and answers with
nothing, `204`. The **view** is one stream per page, opened when the page loads and held open,
which renders the state and sends it whenever it changes. A command never patches the page
itself, and the stream never changes state, so every tab that shows the state gets every change,
whichever tab caused it.

```kotlin sample=declarations
val tally = kotlinx.coroutines.flow.MutableStateFlow(0)

fun tallyView(count: Int): List<DatastarEvent> =
    listOf(PatchElements("""<p id="tally">$count</p>""", selector = "#view", mode = ElementPatchMode.INNER))
```

```kotlin sample=ktor-routing
get("/tally/view") {
    call.respondDatastar {
        sendLatest(tally, minInterval = 50.milliseconds, resumeFrom = call.request.headers["last-event-id"]) {
            tallyView(it)
        }
    }
}
post("/tally/increment") {
    tally.update { it + 1 }
    call.respond(HttpStatusCode.NoContent)
}
```

```kotlin sample=html
div {
    id = "view"
    dataInit(get("/tally/view") { requestCancellation = FetchOptions.RequestCancellation.CLEANUP })
}
button {
    dataOnClick(post("/tally/increment"))
    +"One more"
}
```

`sendLatest` renders the state and sends it, the first time at once and then on every change. It
renders only the newest state when several arrive together, at most one render per
`minInterval`, and nothing when a render would send the same bytes as the last. So the render
can be the whole region, every time, and the client's morph works out what changed. A
`StateFlow` starts with the current value, which is what a stream that has just opened needs.

Four rules keep the stream alive:

- **Patch inside the element that opens the stream, not the element itself.** `#view` carries the
  `data-init`; the patch goes into it with `INNER`. A patch that replaced the element would run its
  `data-init` again and restart the stream, and one that removed it would leave the stream
  running with nothing on the page to patch.
- **Use `requestCancellation = CLEANUP`.** Under the default, `auto`, a request is aborted when
  another one goes to the same URL with the same method, from anywhere on the page. `cleanup`
  also aborts it when its element leaves the page, so a stream does not outlive the part of the
  page that opened it.
- **Let the stream alone patch its region.** A render that would send the same bytes as the last
  is skipped, which is only right while nothing else has changed the region in between. Commands
  answer `204` and leave the page to the stream.
- **Send the state, not the change.** A reader whose stream dropped gets the whole region again on
  reconnect and cannot drift. Each render's fingerprint goes out as its event id; pass the
  `last-event-id` header back as `resumeFrom`, and a reconnect to an unchanged state sends
  nothing at all.

Over HTTP/1.1 a browser holds six connections per origin, across every tab, and a stream
occupies one for as long as it is open. Serve over HTTP/2, or keep to one stream per page.

`@get` closes the stream while the tab is hidden and opens it again when it comes back, which
the fingerprint makes cheap; `openWhenHidden = true` keeps it open instead. The stream also wants
a [heartbeat](/operations/#heartbeats) if a proxy sits in front of it. Testing the whole loop,
command in and patch out on a stream already open, is on [Testing](/testing/).

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
        patchSignals("q" to query)
        replaceUrl(searchUrl(query))
    }
}
```

The search box sends what the reader typed as `q`, the same parameter a bookmark carries, so the
route reads it one way for both:

```kotlin sample=html
input {
    id = "q"
    dataBind("q")
    dataOnInput($$"@get('/search?q=' + encodeURIComponent($q))") { debounce = 300.milliseconds }
}
```

`searchResults` goes through `interpolate`, because the query is the reader's own text and lands
in HTML; [Strings](/strings/) has why. `searchUrl` is the one place the URL is built, so the
browser's code never learns how, and there is nothing to keep in step.

`replaceUrl` swaps the current history entry, so a search that changes on every keystroke does
not fill the back button. `pushUrl` adds an entry, for a step the reader should be able to go back
from, a row opened or a step taken. Both quote the URL as a JavaScript string. The browser refuses
a URL of another origin, so pass a path or a URL on the page's own origin.

After `pushUrl`, Back changes the URL without reloading, and the page hears a `popstate` on
`window`. Ask the server for the URL the reader returned to:

```kotlin sample=html
div {
    dataOn("popstate", "@get(location.pathname + location.search)") { window = true }
}
```

That `@get` is a Datastar request, so the route above answers it with the patch. Patch everything
the URL names, the search box included, or the page and the address bar disagree. Its answer must
not push the URL again, or Back only ever leads forward; leaving the URL alone is safest, since the
browser has already put it there. The route above patches `q` with the results for that reason.

Datastar Pro's `dataReplaceUrl` does the replacing from the client instead, from an expression on
the page.
