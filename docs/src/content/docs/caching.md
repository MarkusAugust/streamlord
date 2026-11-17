---
title: "Caching"
description: "Answer a table as plain HTML with an ETag, and the browser keeps it and asks before it uses it again."
---

> *What I have taken, I keep. What I keep, I do not carry up the hill twice.*
>
> Gorvek of Bonereach

A stream is not cached. Every SSE response carries `Cache-Control: no-cache`, because Datastar's
SDK specification requires it, and the browser reads it as it arrives. An answer that stays the
same until your data changes, such as a page of a table, goes out as one `text/html` body
instead: `respondElements` in Ktor, `datastarElements` in Spring. Datastar patches it in as it
would the elements of a stream, and the browser caches it like any other response, since `@get`
calls the browser's own `fetch` and sets no cache mode of its own.

Streamlord sets no cache header on such an answer. This page shows which ones to set, on each
adapter, and the [live page](/live/#caching) runs it against the deployed service.

## What goes over the wire

The first answer carries a version of what it holds, the ETag:

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

If nothing changed, the server answers without a body:

```text
HTTP/1.1 304 Not Modified
Cache-Control: private, no-cache
ETag: "v42-2"
```

The browser then hands Datastar the table it kept, as a `200`, and Datastar patches it in. Datastar
reads the body of a `200` and of nothing else, and it never sees the `304`. The `304` has to carry
the same `ETag` and `Cache-Control` as the `200` (RFC 9110, section 15.4.5), since a cache in
between takes its rules from them. That is the line the adapters below get right, and the one
that is easy to miss.

What a `304` saves is building the table and sending it, not the round trip. The server still has
to tell whether anything changed, so the version has to be cheaper than the rows: the newest
change time of the orders on that page, or a counter you raise on every write.

## Which signals go in the URL

`@get` sends the signals in the query string, as `datastar=`. The URL is what the browser keys
its cache on, so a page with a search field or a checkbox would ask for a new URL on every
keystroke, and nothing would ever be reused. Leave out the signals the answer does not depend on:

```html
<button data-on:click="@get('/orders?page=2', {filterSignals: {exclude: /.*/}})">Page 2</button>
```

The query string then holds `datastar=%7B%7D`, the same on every click. In Chrome with Datastar
1.0.5, a field bound to a signal on the same page gave a new URL and a full answer on every
change without the filter, and the kept table every time with it. If the answer does depend on a
signal, keep that one: `{filterSignals: {include: /^page$/}}`.

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
        val check = EntityTagVersion(etag).check(call.request.headers)
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
and its `check` compares the way the RFC asks: it takes a weak `W/"…"` tag and a list of several.

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

## What the plugins do not do

The frameworks have filters and plugins that look as if they did this for you. Each one was
tried with `respondElements` or `datastarElements`, on Spring 7.0.10 and Ktor 3.6.1.

- **Spring's `ShallowEtagHeaderFilter`** makes the ETag from a hash of the finished body and
  answers `304` when it matches. The table is built every time, so it saves sending, not building.
  It sets no `Cache-Control`. A stream goes past it unheld, see
  [Spring WebMVC](/spring-webmvc/).
- **Ktor's `ConditionalHeaders`** does nothing for `respondElements` unless you give it a
  `version { }`. With one it sets the ETag and answers `304`, but the table is built every time,
  and it sets no `Cache-Control`.
- **Ktor's `CachingHeaders`** sets `Cache-Control` by a rule of your own, and a rule that matches
  every response matches the streams too. With `max-age=60` for everything, a stream went out with
  `Cache-Control: no-cache, max-age=60`. Match on the content type instead, and use it in place of
  the header in the route, not beside it:

```kotlin sample=declarations
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cachingheaders.CachingHeaders

fun Application.cacheTablesOnly() {
    install(CachingHeaders) {
        options { _, content ->
            when (content.contentType?.withoutParameters()) {
                ContentType.Text.Html -> CachingOptions(CacheControl.NoCache(CacheControl.Visibility.Private))
                else -> null
            }
        }
    }
}
```
