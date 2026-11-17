---
title: "Caching"
description: "Answer a table as plain HTML with an ETag, and the browser keeps it and asks before it uses it again."
---

> *What I have taken, I keep. What I keep, I do not carry up the hill twice.*
>
> Gorvek of Bonereach

A stream is not cached. Every stream response Streamlord builds carries `Cache-Control: no-cache`,
because Datastar's SDK specification requires it, and the browser reads it as it arrives. An
answer that stays the same until your data changes, such as a page of a table, goes out as one
`text/html` body instead: `respondElements` in Ktor, `datastarElements` in Spring. Datastar
patches it in as it would the elements of a stream, and the browser caches it like any other
response, since `@get` calls the browser's own `fetch` and sets no cache mode of its own.

Streamlord sets no cache header on such an answer. This page shows which ones to set, on each
adapter, and the [live page](/live/#caching) runs it against the deployed service. An answer that
also sets signals carries them in its HTML, and
[Signals and codecs](/signals/#signals-in-a-plain-answer) shows the way that does not fail quietly.

## What caching saves

| Headers on the answer             | Reaches your server       | Builds the table          | Sends the table           | Always current |
| --------------------------------- | ------------------------- | ------------------------- | ------------------------- | -------------- |
| none                              | every time                | every time                | every time                | yes            |
| `no-cache` and an ETag            | every time                | only when it changed      | only when it changed      | yes            |
| `private, max-age`                | not until it runs out     | not until it runs out     | not until it runs out     | no             |
| `public, max-age`, behind a CDN   | once per max-age, for all | once per max-age, for all | once per max-age, for all | no             |

An ETag does not spare the server the request. It spares it the work: reading the rows and
writing the markup, and sending what it wrote. Fifty rows of three short cells, each one
`<tr><td>1001</td><td>Gorvek</td><td>three swords</td></tr>`, come to 2,952 bytes of HTML with
the table around them, and the `304` that stands in for them has headers and no body. How much
the work weighs depends on your database and your markup, not on Streamlord. Only `max-age` keeps requests from reaching the server at
all, and it pays for that in data that can be out of date; see [ETag or max-age](#etag-or-max-age).

## What goes over the wire

An ETag, short for entity tag, is a standard HTTP header, part of HTTP itself and not of
Streamlord or Datastar. The server puts it on an answer to name the version it holds: a short text in
double quotes, such as `"v42-2"`, of letters, digits and punctuation, with no space and no quote
inside. The browser's own cache keeps it with the answer and sends it
back, and the server only asks whether it is the one it would send now.
[MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/ETag) has the details.

The first answer carries it:

```text
HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
Cache-Control: private, no-cache
ETag: "v42-2"

<table id="orders">…</table>
```

The browser keeps the table. `no-cache` does not stop it from keeping it: it says the browser has
to ask before it uses it again. `private` keeps it out of a shared cache, a proxy or a CDN, which
is right for anything that belongs to a signed-in user.

The next time the page asks for the same URL, the browser adds the ETag it holds:

```text
GET /orders?page=2&datastar=%7B%7D
If-None-Match: "v42-2"
```

`datastar=%7B%7D` is an empty set of signals, which is what the filter in the
[next section](#which-signals-go-in-the-url) leaves. If nothing changed, the server answers
without a body:

```text
HTTP/1.1 304 Not Modified
Cache-Control: private, no-cache
ETag: "v42-2"
```

The browser then hands Datastar the table it kept, as a `200`, and Datastar patches it in. Datastar
reads the body of a `200` and of nothing else, and it never sees the `304`. The `304` has to carry
the same `ETag` and `Cache-Control` as the `200`: RFC 9110 requires it, in section 15.4.5. A
cache sent a `304` without them keeps the ones it stored, so a browser copes, but the answer
breaks the RFC, and a cache in between is owed the rules. That is the line the adapters below get
right, and the one that is easy to miss.

The server still has to tell whether anything changed, so the version has to be cheaper than the
rows. A counter you raise on every write is the simplest. The time of the newest change on the
page is not enough: a deleted row leaves no time behind, and moves the rows after it onto other
pages. The tag also has to cover everything else the answer depends on. If two users see
different tables at the same URL, put the user in it; and put the version of your code in it, so
that a deploy that changes the markup changes the tag.

## Which signals go in the URL

`@get` sends the signals in the query string, as `datastar=`. The URL is what the browser keys
its cache on, so once a search field or a checkbox on the page has changed, the next press asks
for a new URL, and nothing is reused. Leave out the signals the answer does not depend on:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=html
button {
    dataOnClick(get("/orders?page=2") { filterSignals = SignalFilter.exclude(".*") })
    +"Page 2"
}
```

```kotlin tab="String" group=markup sample=declarations
val pageTwoButton =
    """<button data-on:click="@get('/orders?page=2', {filterSignals: {exclude: /.*/}})">Page 2</button>"""
```

```html tab="Template" group=markup
<button data-on:click="@get('/orders?page=2', {filterSignals: {exclude: /.*/}})">Page 2</button>
```

The query string then holds `datastar=%7B%7D`, the same on every press. In Chrome with Datastar
1.0.5, a field bound to a signal on the same page gave a new URL and a full answer after every
change without the filter, and the kept table every time with it.

If the answer does depend on a signal, send that one and no other. A table the reader can sort
is the common case:

```html
<div data-signals="{sort: 'order'}">
    <select data-bind:sort
            data-on:change="@get('/orders?page=2', {filterSignals: {include: /^sort$/}})">
        <option value="order">Order</option>
        <option value="who">Who</option>
    </select>
</div>
```

The handler reads it with the rest of the signals, `call.readSignals().string("sort")` in Ktor,
as [Signals and codecs](/signals/) shows. Each page in each sort is then a URL of its own, kept
on its own. In Chrome with Datastar 1.0.5, sorting another way gave a new URL, and sorting back
gave the very same URL as the first time, so the table kept for it was used again. The first look
at each sort is a `200`. The ETag needs no sort in it, since the browser keeps each URL apart; it
changes when the data does. Filter the sort out, as `exclude: /.*/` does, and the server never
hears it: every sort is the same URL and the same answer, and sorting does nothing.

One more thing decides where the page goes. Datastar cancels a request still running when another
goes to the same URL as written in `@get`, before the signals are added to it. With the page in
that URL, page 2 and then page 3 are two URLs, both run, and if page 2 answers last the table shows
page 2 while the reader asked for page 3. In Chrome with Datastar 1.0.5, two presses on two such
URLs both ran to the end, and two on one URL with different signals ended the first at once. So
when presses can come fast, send the page as a signal too, `{filterSignals: {include:
/^(page|sort)$/}}`, and read it from the signals: every press goes to one URL and cancels the one
before, and the cache works as before, since it keys on the URL with the signals in it. The
[live page](/live/#caching) does it this way.

## Ktor

```kotlin sample=declarations
import io.ktor.http.HttpHeaders
import io.ktor.http.content.EntityTagVersion
import io.ktor.http.content.VersionCheckResult
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.orderRoutes(orders: Orders) {
    get("/orders") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val etag = "\"${orders.version(page)}\""
        call.response.header(HttpHeaders.CacheControl, "private, no-cache")
        call.response.header(HttpHeaders.ETag, etag)
        val check =
            runCatching { EntityTagVersion(etag).check(call.request.headers) }
                .getOrDefault(VersionCheckResult.OK)
        if (check != VersionCheckResult.OK) {
            call.respond(check.statusCode)
            return@get
        }
        call.respondElements(orderTable(orders.page(page), page))
    }
}
```

`Orders` stands for your repository: `version(page)` is the cheap question, `page(page)` the
expensive one, and `orderTable` builds the markup. Both headers are set before the check, so the
`304` carries them too. `EntityTagVersion` comes with Ktor itself, in `ktor-http`, from Ktor 3.0,
and its `check` compares tags the way the RFC asks: it takes a weak tag, `W/"…"`, which promises
the same content but not the same bytes, and a list of several. It answers `If-None-Match: *` with
the table, where the RFC asks for a `304`, but no browser sends that. A header it cannot read,
such as `"a b"` or a tag with `;q=0.5` after it, makes it throw, and left alone that is a `500`;
`runCatching` reads it as no match and answers the table. Keep spaces and quotes out of your own
version too: Ktor refuses such a tag, and every request would then be answered in full.

## Spring WebMVC

```kotlin sample=declarations
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.WebRequest

@RestController
class OrderController(
    private val orders: Orders,
) {
    @GetMapping("/orders")
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        request: WebRequest,
        response: HttpServletResponse,
    ): ResponseEntity<String>? {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noCache().cachePrivate().headerValue)
        if (request.checkNotModified(orders.version(page))) return null
        return datastarElements(orderTable(orders.page(page), page))
    }
}
```

`checkNotModified` compares the tag with `If-None-Match`, sets the `ETag`, and on a match answers
`304` by itself; returning `null` tells Spring the response is done. It answers before any
`ResponseEntity` exists, so a `Cache-Control` set on the entity never reaches the `304`. Set it on
the servlet response before the check, as above. On a real Tomcat with Spring 7.0.10, the entity's
header was in the `200` and missing from the `304`.

## Spring WebFlux

```kotlin sample=declarations
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
class OrderFluxController(
    private val orders: Orders,
) {
    @GetMapping("/orders")
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        exchange: ServerWebExchange,
    ): ResponseEntity<String>? {
        exchange.response.headers.setCacheControl(CacheControl.noCache().cachePrivate())
        if (exchange.checkNotModified(orders.version(page))) return null
        return datastarElements(orderTable(orders.page(page), page))
    }
}
```

The same shape on the exchange: the header first, then the check.

## ETag or max-age

`max-age` lets the browser use what it kept without asking at all:

```text
Cache-Control: private, max-age=30
```

For thirty seconds nothing leaves the browser. In Chrome with Datastar 1.0.5, going back to a page
inside that time sent no request, and a table changed on the server in between was not seen
until the time ran out. That includes the user's own change: someone who edits an order on page 2,
goes to page 3 and back, sees the order as it was.

So for data your users change, use `no-cache` and an ETag, as above: always current, and nearly
free when nothing changed. Use `max-age` for what seldom changes, a product list or a list of
countries, and `public` instead of `private` when it is the same for everyone, so a CDN can keep it
too. The two also combine: `private, max-age=10` with an ETag is used as it is for ten seconds
and asked about after that.

## When to leave it out

Not every answer is worth a tag. Leave it out when the answer is cheap to build, since a `304`
then saves next to nothing and the version is one more thing to keep right. Leave it out when the
data changes on nearly every request, since the tag never matches and each answer pays for the
check as well. And leave it out when the answer depends on so much that the tag costs as much to
work out as the table: a stream, or no cache at all, is the honest answer there.

## What the plugins do not do

The frameworks have filters and plugins that look as if they did this for you. Each one was
tried by hand with `respondElements` or `datastarElements`, on Spring 7.0.10 and Ktor 3.6.1, and
what is said of `CachingHeaders` is also held by the tests that run this page's examples.

- **Spring's `ShallowEtagHeaderFilter`** makes the ETag from a hash of the finished body and
  answers `304` when it matches. The table is built every time, so it saves sending, not building.
  It sets no `Cache-Control`. A stream goes past it unheld, see
  [Spring WebMVC](/spring-webmvc/).
- **Ktor's `ConditionalHeaders`** answers `304` for a version you give it in `version { }`, or for
  an `ETag` already set on the response. Either way it acts after the table is built, so it saves
  sending, not building, and it sets no `Cache-Control`.
- **Ktor's `CachingHeaders`** sets `Cache-Control` by a rule of your own. A rule on the content
  type does not reach the `304`, which has none, so the `304` goes out without the
  `Cache-Control` the `200` had. A rule for every response, installed for the whole application,
  reaches the streams too: with `max-age=60` for everything, a stream went out with
  `Cache-Control: no-cache, max-age=60`. A rule for every response installed in the table's
  `route { }` alone does fit, on the `200` and the `304`; so does the header in the route, as
  above.
