---
title: "Loading indicators"
description: "Show that a request is on its way with data-indicator, the rules it keeps, and one indicator for a whole widget."
---

> *A man who goes quiet in the middle of a fight is either dead or thinking. Say which.*
>
> Gorvek of Bonereach

A reader who presses a button and sees nothing presses it again. Datastar's `data-indicator` turns
a signal on while a request is on its way and off when it is over, so the page can say so the
moment the press lands, before the server has answered anything. It keeps three rules that, broken,
fail without a word, one naming habit saves trouble, and a few requests keep it on. Each of them
below was tried in Chrome 157 with Datastar 1.0.5, against a stream the server held open for
twelve seconds and against a plain HTML answer. Where Datastar's own documentation says nothing
about a behaviour, the page says so, and the 1.0.5 client is the source.

## What data-indicator follows

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun FlowContent.nextPage() {
    button {
        dataIndicator("orders._loading")
        dataOnClick(get("/orders?page=2"))
        +"Next page"
    }
    p {
        hidden = true
        dataAttr("hidden", "!" + signal("orders._loading"))
        +"Loading…"
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val nextPageHtml =
    $$"""
    <button data-indicator="orders._loading" data-on:click="@get('/orders?page=2')">Next page</button>
    <p hidden data-attr:hidden="!$orders._loading">Loading…</p>
    """.trimIndent()
```

```html tab="Template" group=markup
<button data-indicator="orders._loading" data-on:click="@get('/orders?page=2')">Next page</button>
<p hidden data-attr:hidden="!$orders._loading">Loading…</p>
```

The signal is `false` until the button's request starts, `true` while it runs, and `false` again
when it is over. For a stream, over means the stream closed. For one HTML or JSON answer, it means
the answer has been patched in: the start came first, then the patch, then the end. A request that
ends in an error, or one a newer request [cancelled](#one-name-one-element), is over too; the few
that are not come [further down](#requests-that-keep-it-on).

It follows the requests of its own element and no other. Datastar's reference says only that the
signal is true "while a fetch request is in flight"; the 1.0.5 client skips any request whose
element is not the one the attribute stands on. Put on a `div` around the button, it stayed `false`
while the button's request ran.

The message starts out `hidden`, so it stays out of sight in the moment before Datastar has read
the page, and `data-attr:hidden` takes over from there. Datastar's reference suggests a
`style="display: none"` with `data-show` against that moment, and this page departs from it on
purpose: a Content Security Policy without `'unsafe-inline'` for styles refuses a `style`
attribute, and on the page [Security](/security/#a-page-that-really-runs-under-one) shows running
under one, `style="display: none"` was ignored and the message stood in plain view. Not
`data-show` with `hidden` either: it sets `display` and leaves the attribute where it is, and with
both the message stayed hidden while the request ran. If a class on the message sets `display`,
give it `[hidden] { display: none }` too: a class rule outranks the browser's own rule for
`hidden`, and in Chrome a `display: flex` class showed the message despite it.

## One name, one element

Two elements that share an indicator's name turn each other's off. Two buttons with
`data-indicator="orders._loading"` and the same `@get('/orders?page=2')`: the second press
cancelled the first request, as Datastar does for a request with the same method to the same URL
as written in `@get`, and the end of the cancelled one set the signal to `false` while the second
still ran. One element pressed twice stayed `true`, since an element counts its own requests.

So give each element its own name, or, where many controls drive one table, let one element send
for all of them: [one indicator for the whole widget](#one-indicator-for-a-whole-widget).

## The indicator before data-init

Datastar reads an element's attributes in the order they are written, and the elements of a page
in document order, a parent before its children; its reference says the indicator's signal "must
be created before the fetch request is initialized". The DSL writes attributes in the order you
call it. On an element that sends a request as it appears, the indicator comes first:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun FlowContent.ordersOnArrival() {
    div {
        dataIndicator("orders._loading")
        dataInit(get("/orders"))
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val ordersOnArrivalHtml =
    """<div data-indicator="orders._loading" data-init="@get('/orders')"></div>"""
```

```html tab="Template" group=markup
<div data-indicator="orders._loading" data-init="@get('/orders')"></div>
```

Written the other way round, the request was on its way before the indicator listened, and it
stayed `false` for that request.

## A name that stays in the browser

The indicator's signal is the page's own business, and a name with an underscore part keeps it
there. Datastar sends every signal except those whose name, or any part of it, starts with one; the
1.0.5 client's filter is `/(^|\.)_/`, which its reference prints as `/(^_|\._).*/`. A request from a
page holding `orders.page` and `orders._loading` sent `page` and not `_loading`. Without the
underscore it goes out with every request: a button with `data-indicator="orders.loading"` sent
`"orders":{"loading":true}`, since the indicator is turned on before the signals are read. See
[Signals that stay in the browser](/signals/#signals-that-stay-in-the-browser). With the widget's
own path in front, `orders._loading` and `invoices._loading`, two widgets on one page keep two
indicators.

## One indicator for a whole widget

A table with pages, a sort and a size has many controls, and the rule of one name per element
would give each its own indicator. Datastar's
[own advice](https://data-star.dev/how_tos/keep_datastar_code_dry) for many buttons is one
listener on their parent, since a click bubbles up to it. Let that parent send every request, and
one indicator on it covers them all:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
val loadOrders = get("/orders") { filterSignals = SignalFilter.include("^orders\\.page$") }

fun FlowContent.ordersWidget() {
    div {
        id = "orders"
        dataSignals("{orders: {page: 1}}")
        dataIndicator("orders._loading")
        dataOnClick("evt.target.closest('button') && " + loadOrders)
        dataInit(loadOrders)
        p {
            hidden = true
            dataAttr("hidden", "!" + signal("orders._loading"))
            +"Loading…"
        }
        div {
            id = "orders-table"
            +"Loading the orders…"
        }
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val ordersWidgetHtml =
    $$"""
    <div id="orders"
         data-signals="{orders: {page: 1}}"
         data-indicator="orders._loading"
         data-on:click="evt.target.closest('button') && @get('/orders', {filterSignals: {include: /^orders\.page$/}})"
         data-init="@get('/orders', {filterSignals: {include: /^orders\.page$/}})">
        <p hidden data-attr:hidden="!$orders._loading">Loading…</p>
        <div id="orders-table">Loading the orders…</div>
    </div>
    """.trimIndent()
```

```html tab="Template" group=markup
<div id="orders"
     data-signals="{orders: {page: 1}}"
     data-indicator="orders._loading"
     data-on:click="evt.target.closest('button') && @get('/orders', {filterSignals: {include: /^orders\.page$/}})"
     data-init="@get('/orders', {filterSignals: {include: /^orders\.page$/}})">
    <p hidden data-attr:hidden="!$orders._loading">Loading…</p>
    <div id="orders-table">Loading the orders…</div>
</div>
```

The buttons in the table set the page and send nothing themselves:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun TagConsumer<*>.ordersPage(page: Int) {
    div {
        id = "orders-table"
        p { +"Page $page" }
        if (page < 3) {
            button {
                dataOnClick(set("orders.page", page + 1))
                +"Next page"
            }
        }
    }
}
```

```kotlin tab="String" group=markup sample=declarations
fun ordersPageHtml(page: Int): String =
    """<div id="orders-table"><p>Page $page</p>""" +
        (if (page < 3) """<button data-on:click="${'$'}orders.page = ${page + 1}">Next page</button>""" else "") +
        "</div>"
```

```html tab="Template" group=markup
<div id="orders-table">
    <p>Page 1</p>
    <button data-on:click="$orders.page = 2">Next page</button>
</div>
```

```kotlin tab="Ktor" group=adapter label="Adapter" sample=ktor-routing
get("/orders") {
    val page = call.readSignals().obj("orders")?.int("page") ?: 1
    call.respondElements(elements { ordersPage(page) })
}
```

```kotlin tab="Spring" group=adapter sample=spring
@GetMapping("/orders")
fun orders(request: HttpServletRequest): ResponseEntity<String> {
    val page = request.readSignals().obj("orders")?.int("page") ?: 1
    return datastarElements(elements { ordersPage(page) })
}
```

A press on Next page runs the button's own `data-on:click` first, which sets `$orders.page`, and
then the click reaches `#orders`, which sends the request with the page in it. Every request of
the widget comes from `#orders`, so its one `data-indicator` counts them all: when page 3 was
pressed while page 2 was still on its way, the second cancelled the first, both being one URL as
written, and the indicator stayed `true` until the second was over. A button the answer takes
away, as on the last page, does not matter, since it sent nothing. A `<select>` for the page size
works the same with a `data-on:change` on `#orders` beside the click, as a change bubbles too.

The page goes in the signals and not in the URL for the same reason: `@get('/orders?page=3')`
and `@get('/orders?page=2')` are two URLs, both run, and the slower answer lands last. With the
String tab the answer is `ordersPageHtml(page)`; with a template, your engine renders the same
fragment. To send the page back as the server settled it, when a page asked for no longer exists,
put an element that sets it in the answer, as [Signals and codecs](/signals/#signals-in-a-plain-answer)
shows.

## Requests that keep it on

A few requests start and are not over, and their indicator stays on. Datastar's documentation says
nothing of either; both are in the 1.0.5 client.

- An answer that is a script, `text/javascript` from `respondScript` in Ktor or `datastarScript`
  in Spring. Datastar runs it and does not end the request; in Chrome the script ran and the
  indicator stayed `true`, until a second press with the same method to the same URL cancelled
  the first request and ended it. Send the script in a stream, which ends when the stream closes:

```kotlin tab="Ktor" group=adapter label="Adapter" sample=ktor-routing
post("/orders/print") {
    call.respondDatastar { executeScript("window.print()") }
}
```

```kotlin tab="Spring" group=adapter sample=spring-controller
@RestController
class PrintController(private val streamlord: Streamlord) {
    @PostMapping("/orders/print")
    fun print(
        response: HttpServletResponse,
    ): org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody =
        response.datastarStream(streamlord) { executeScript("window.print()") }
}
```

- A button that sends a form, `@get('/orders', {contentType: 'form'})`, while the form fails its
  validation. Datastar reports the start and nothing after it, and nothing ends it. Send the form
  from its own submit instead, as Datastar's form example does: the browser checks it first and
  sends nothing while it is invalid.

```html
<form data-indicator="orders._searching" data-on:submit="@get('/orders', {contentType: 'form'})">
```

A request to a server that cannot be reached is over in the end, but late: Datastar tries again,
with the indicator on, and after eight seconds against a closed port it was still `true`, with
four `retrying` events behind it. The defaults the reference gives, a second at first, doubling, at
most thirty, ten tries, come to some three minutes, and the indicator goes out then.
`{retryMaxCount: 2}` on the action gave up after three seconds, and
[Components](/components/#showing-a-change-before-the-server-answers) shows how to tell the reader
the request failed.

## A stream that stays open

An indicator is on until its request is over, and a [live view](/live-views/) holds a request that
is never over: an indicator on the element that holds it never goes out. A command is over as soon
as it is accepted, before the change it makes arrives on the stream, so an indicator on the command
goes out too early. Datastar's [Tao](https://data-star.dev/guide/the_tao_of_datastar) has the way
for this: show the waiting state yourself as the command goes, and let the stream's update clear
it.

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun FlowContent.ordersView() {
    div {
        id = "view"
        dataOnFetch(
            "['${FetchEventType.ERROR}', '${FetchEventType.RETRIES_FAILED}'].includes(evt.detail.type) " +
                "&& el.classList.remove('waiting')",
        )
        button {
            dataOnClick(statements("el.closest('#view').classList.add('waiting')", post("/orders/next")))
            +"Next order"
        }
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val ordersViewHtml =
    """
    <div id="view" data-on:datastar-fetch="['error', 'retries-failed'].includes(evt.detail.type) && el.classList.remove('waiting')">
        <button data-on:click="el.closest('#view').classList.add('waiting'); @post('/orders/next')">Next order</button>
    </div>
    """.trimIndent()
```

```html tab="Template" group=markup
<div id="view" data-on:datastar-fetch="['error', 'retries-failed'].includes(evt.detail.type) && el.classList.remove('waiting')">
    <button data-on:click="el.closest('#view').classList.add('waiting'); @post('/orders/next')">Next order</button>
</div>
```

```css
#view.waiting button { opacity: 0.5; }
```

The stream renders `#view` without the class, and its patch morphs the element: in Chrome, the
class went on with the press and came off when the new view arrived. The patch has to reach `#view`
itself, as the default mode does with the same id; one that only fills it, `inner`, leaves its
class alone. A command that fails sends no new view, so the listener takes the class off on an
`error`: with a command that answered 404 and no listener, the class stayed.

## The first view

Before Datastar has read the page there is no signal to show, and the first request starts only
once it has. So the first view says so in its markup, as `#orders-table` does above,
`Loading the orders…`, and the first answer replaces it. The `data-init` on `#orders` sends that
first request after its `data-indicator`, so the indicator's message shows for it as well.
