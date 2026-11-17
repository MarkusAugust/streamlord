---
title: "Loading indicators"
description: "One loading indicator for a whole table widget with data-indicator, the rules it rests on, and the requests that keep it on."
---

> *A man who goes quiet in the middle of a fight is either dead or thinking. Say which.*
>
> Gorvek of Bonereach

A reader who presses a button and sees nothing presses it again. Datastar's `data-indicator` turns
a signal on while a request is on its way and off when it is over, so the page can say so the
moment the press lands. This page builds one indicator for a table with pages, a sort and a page
size, and then gives the rules it rests on. Everything on it was tried in Chrome 157 with
Datastar 1.0.5. Where Datastar's documentation says nothing about a behaviour, the page says so,
and the 1.0.5 client is the source.

## One indicator for a whole widget

A table has many controls. Datastar's [advice](https://data-star.dev/how_tos/keep_datastar_code_dry)
for many buttons is one listener on the element around them, since a click bubbles up to it. Let
that element send every request of the widget, and one indicator on it covers them all:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
val loadOrders = get("/orders") { filterSignals = SignalFilter.include("^orders\\.(page|sort|size)$") }

fun FlowContent.ordersWidget() {
    div {
        id = "orders"
        dataSignals("{orders: {page: 1, sort: 'date', size: 10}}")
        dataIndicator("orders._loading")
        dataOnClick("evt.target.closest('button') && " + loadOrders)
        dataOnChange("evt.target.closest('select') && " + loadOrders)
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
         data-signals="{orders: {page: 1, sort: 'date', size: 10}}"
         data-indicator="orders._loading"
         data-on:click="evt.target.closest('button') && @get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}})"
         data-on:change="evt.target.closest('select') && @get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}})"
         data-init="@get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}})">
        <p hidden data-attr:hidden="!$orders._loading">Loading…</p>
        <div id="orders-table">Loading the orders…</div>
    </div>
    """.trimIndent()
```

```html tab="Template" group=markup
<div id="orders"
     data-signals="{orders: {page: 1, sort: 'date', size: 10}}"
     data-indicator="orders._loading"
     data-on:click="evt.target.closest('button') && @get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}})"
     data-on:change="evt.target.closest('select') && @get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}})"
     data-init="@get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}})">
    <p hidden data-attr:hidden="!$orders._loading">Loading…</p>
    <div id="orders-table">Loading the orders…</div>
</div>
```

The controls in the table set signals and send nothing themselves:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun TagConsumer<*>.ordersTable(page: Int) {
    div {
        id = "orders-table"
        button {
            dataOnClick(statements(set("orders.sort", "who"), set("orders.page", 1)))
            +"Sort by who"
        }
        select {
            dataBind("orders.size")
            dataOnChange(set("orders.page", 1))
            option { +"10" }
            option { +"25" }
        }
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
fun ordersTableHtml(page: Int): String =
    $$"""
    <div id="orders-table">
        <button data-on:click="$orders.sort = 'who'; $orders.page = 1">Sort by who</button>
        <select data-bind="orders.size" data-on:change="$orders.page = 1"><option>10</option><option>25</option></select>
        <p>Page $$page</p>
    """.trimIndent() +
        (if (page < 3) $$"""<button data-on:click="$orders.page = $${page + 1}">Next page</button>""" else "") +
        "</div>"
```

```html tab="Template" group=markup
<div id="orders-table">
    <button data-on:click="$orders.sort = 'who'; $orders.page = 1">Sort by who</button>
    <select data-bind="orders.size" data-on:change="$orders.page = 1"><option>10</option><option>25</option></select>
    <p>Page {{ page }}</p>
    {% if page < 3 %}
    <button data-next="{{ page + 1 }}" data-on:click="$orders.page = Number(el.dataset.next)">Next page</button>
    {% endif %}
</div>
```

```kotlin tab="Ktor" group=adapter label="Adapter" sample=ktor-routing
get("/orders") {
    val page = call.readSignals().obj("orders")?.int("page") ?: 1
    call.respondElements(elements { ordersTable(page) })
}
```

```kotlin tab="Spring" group=adapter sample=spring
@GetMapping("/orders")
fun orders(request: HttpServletRequest): ResponseEntity<String> {
    val page = request.readSignals().obj("orders")?.int("page") ?: 1
    return datastarElements(elements { ordersTable(page) })
}
```

The sort and the size come the same way, `obj("orders")?.string("sort")` and
`obj("orders")?.int("size")`, for your query. With the String tab the answer is
`ordersTableHtml(page)`; with a template, your engine renders it.

A press on Next page runs the button's own `data-on:click` first, which sets `$orders.page`, and
then the click reaches `#orders`, which sends the request with the page in it. A change of the
size works the same through `data-on:change`, after the `select` has set its signal. Every request
of the widget comes from `#orders`, so its one `data-indicator` counts them all: a press while
another request was on its way cancelled the older one, both being one URL as written, and the
indicator stayed `true` until the newer one was over. The signals go in the request and not in the
URL for that reason: `@get('/orders?page=3')` and `@get('/orders?page=2')` are two URLs, both run,
and the slower answer lands last.

`filterSignals` sends the signals the server needs and no others, the way
[Caching](/caching/#which-signals-go-in-the-url) explains. Leave one out and the server never hears
it. The indicator's own signal, `orders._loading`, starts with an underscore part, so Datastar
keeps it in the browser in any case; [further down](#a-name-that-stays-in-the-browser) is why.

The first view is the `data-init`, sent after the `data-indicator` on the same element, so the
message shows for it as it does for every press after. Before Datastar has read the page there is
no signal at all, which is why `#orders-table` holds `Loading the orders…` as plain text until the
first answer replaces it.

Some controls need more:

- **A button with an action of its own**, such as a row's `@post('/orders/7/delete')`, would also
  fire the widget's request. Write its click with `data-on:click__stop`, `dataOnClick(...) { stop = true }`
  in the DSL, and the click stops at the button: the widget sent nothing for it.
- **A button inside a `<form>`** submits the form as well, and the browser leaves the page. Give it
  `type="button"`.
- **A widget inside another**: both hear the click, and both sent. Give each widget's element a
  class, `widget`, and add `evt.target.closest('.widget') === el &&` to the front of both
  listeners: then only the nearest widget sent.
- **Two widgets on one page** with the same URL cancel each other's requests, since Datastar
  cancels by method and URL "regardless of which element initiated it". Give each its own URL, as
  `/orders` and `/invoices`, or a part that tells them apart, `/orders?list=open`.

## A request that fails

A request whose answer is an error, a 404 or a 500, is over, and the indicator goes out, but the
1.0.5 client reads the body of a `200` and of nothing else, so the table stays as it was and says
nothing. Answer a failure the widget can show as a `200`, with the table's id and a button to try
again; the widget already sends for any button:

```kotlin tab="Ktor" group=adapter label="Adapter" sample=ktor-routing
get("/orders") {
    val page = call.readSignals().obj("orders")?.int("page") ?: 1
    val table =
        runCatching { elements { ordersTable(page) } }.getOrElse {
            elements { div { id = "orders-table"; p { +"The orders could not be read." }; button { +"Try again" } } }
        }
    call.respondElements(table)
}
```

```kotlin tab="Spring" group=adapter sample=spring
@GetMapping("/orders")
fun ordersOrFailure(request: HttpServletRequest): ResponseEntity<String> {
    val page = request.readSignals().obj("orders")?.int("page") ?: 1
    val table =
        runCatching { elements { ordersTable(page) } }.getOrElse {
            elements { div { id = "orders-table"; p { +"The orders could not be read." }; button { +"Try again" } } }
        }
    return datastarElements(table)
}
```

A server that cannot be reached gives no answer to show. Datastar tries again with the indicator
on: after eight seconds against a closed port it was still `true`, with four `retrying` events
behind it, and the defaults the reference gives, a second at first, doubling, at most thirty, ten
tries, come to some three minutes. `get("/orders") { retryMaxCount = 2 }`, `{retryMaxCount: 2}` in
markup, gave up after three seconds, and the indicator went out.

## What data-indicator follows

The widget rests on four rules, and each holds for any `data-indicator`. The first is what it
follows:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun FlowContent.exportOrders() {
    button {
        dataIndicator("orders._exporting")
        dataOnClick(post("/orders/export"))
        +"Export"
    }
    p {
        hidden = true
        dataAttr("hidden", "!" + signal("orders._exporting"))
        +"Exporting…"
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val exportOrdersHtml =
    $$"""
    <button data-indicator="orders._exporting" data-on:click="@post('/orders/export')">Export</button>
    <p hidden data-attr:hidden="!$orders._exporting">Exporting…</p>
    """.trimIndent()
```

```html tab="Template" group=markup
<button data-indicator="orders._exporting" data-on:click="@post('/orders/export')">Export</button>
<p hidden data-attr:hidden="!$orders._exporting">Exporting…</p>
```

The signal is `false` until the button's request starts, `true` while it runs, and `false` again
when it is over. For a stream, over means the stream closed. For one HTML or JSON answer, it means
the answer has been patched in: the start came first, then the patch, then the end. A request that
ends in an error, or one a newer request cancelled, is over too.

It follows the requests of its own element and no other. Datastar's reference says only that the
signal is true "while a fetch request is in flight"; the 1.0.5 client skips any request whose
element is not the one the attribute stands on. Put on a `div` around the button, it stayed `false`
while the button's request ran. That is why the widget lets one element send.

The message starts out `hidden`, so it stays out of sight in the moment before Datastar has read
the page, and `data-attr:hidden` takes over from there. Datastar's reference suggests a
`style="display: none"` with `data-show` against that moment, and this page departs from it on
purpose: a Content Security Policy without `'unsafe-inline'` for styles refuses a `style`
attribute, and on the [CSP page](/security/#a-page-that-really-runs-under-one) that this site's
service runs, `style="display: none"` was ignored and the message stood in plain view. Not
`data-show` with `hidden` either: it sets `display` and leaves the attribute where it is, and with
both the message stayed hidden while the request ran. If a class on the message sets `display`,
add a rule for that class with `[hidden]`, `.status[hidden] { display: none }`: a class rule
outranks the browser's own rule for `hidden`, and in Chrome a `display: flex` class showed the
message despite it.

## One name, one element

Two elements that share an indicator's name turn each other's off. Two buttons with
`data-indicator="orders._exporting"` and the same `@post('/orders/export')`: the second press
cancelled the first request, as Datastar does for a request with the same method to the same URL
as written in `@get` or `@post`, and the end of the cancelled one set the signal to `false` while
the second still ran. One element pressed twice stayed `true`, since an element counts its own
requests. Give each element its own name, or let one element send, as the widget does.

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

## Requests that keep the indicator on

A few requests start and are not over, and their indicator stays on. Datastar's documentation says
nothing of either; both are in the 1.0.5 client.

- **An answer that is a script**, `text/javascript` from `respondScript` in Ktor or
  `datastarScript` in Spring. Datastar runs it and does not end the request. In Chrome the script
  ran and the indicator stayed `true`; a second press with the same method to the same URL ended
  the first request, but if it is answered with a script as well, it hangs in its turn. Send the
  script in a stream, which ends when the stream closes:

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

- **A button that sends a form**, `@get('/orders', {contentType: 'form'})`, while the form fails
  its validation. Datastar reports the start and nothing after it, and nothing ends it: the
  element's indicator stayed `true` for good, even after a later valid press from the same button
  had come back. Send the form from its own submit instead, as Datastar's form example does: the
  browser checks it first and sends nothing while it is invalid.

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun FlowContent.ordersSearch() {
    form {
        dataIndicator("orders._searching")
        dataOnSubmit(get("/orders/search") { contentType = FetchOptions.ContentType.FORM })
        input(name = "q") { required = true }
        button { +"Search" }
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val ordersSearchHtml =
    """
    <form data-indicator="orders._searching" data-on:submit="@get('/orders/search', {contentType: 'form'})">
        <input name="q" required>
        <button>Search</button>
    </form>
    """.trimIndent()
```

```html tab="Template" group=markup
<form data-indicator="orders._searching" data-on:submit="@get('/orders/search', {contentType: 'form'})">
    <input name="q" required>
    <button>Search</button>
</form>
```

## A stream that stays open

An indicator is on until its request is over, and a [live view](/live-views/) holds a request that
is never over: an indicator on the element that holds it never goes out. A command is over as soon
as it is accepted, before the change it makes arrives on the stream, so an indicator on the command
goes out too early. Datastar's [Tao](https://data-star.dev/guide/the_tao_of_datastar) has the way
for this: show the waiting state yourself as the command goes, and let the stream's update clear
it. With the tally of the live view page, whose stream fills `#view` with `<p id="tally">`:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun FlowContent.tallyCommand() {
    button {
        dataOnClick(statements("document.getElementById('tally').classList.add('waiting')", post("/tally/increment")))
        dataOnFetch(
            "evt.detail.el === el && " +
                "['${FetchEventType.ERROR}', '${FetchEventType.RETRIES_FAILED}'].includes(evt.detail.type) && " +
                "document.getElementById('tally').classList.remove('waiting')",
        )
        +"One more"
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val tallyCommandHtml =
    """
    <button data-on:click="document.getElementById('tally').classList.add('waiting'); @post('/tally/increment')"
            data-on:datastar-fetch="evt.detail.el === el && ['error', 'retries-failed'].includes(evt.detail.type) && document.getElementById('tally').classList.remove('waiting')">One more</button>
    """.trimIndent()
```

```html tab="Template" group=markup
<button data-on:click="document.getElementById('tally').classList.add('waiting'); @post('/tally/increment')"
        data-on:datastar-fetch="evt.detail.el === el && ['error', 'retries-failed'].includes(evt.detail.type) && document.getElementById('tally').classList.remove('waiting')">One more</button>
```

```css
#tally.waiting { opacity: 0.5; }
```

The class goes on `#tally`, the element the stream renders, and not on `#view`, which the stream
only fills: its patch, `INNER`, leaves `#view`'s own class alone. The stream renders `#tally`
without the class, and the morph takes it off: in Chrome, the class went on with the press and came
off when the new tally arrived.

It holds only for a command that always changes what the view shows. A live view sends nothing
when a render is the same as the last, so a command that changes nothing, a Next on the last
order, would leave the class on. Where a command can do that, put a `data-indicator` on the command
instead, which covers the command and not the change.

A command that fails sends no new view, so the button's listener takes the class off on an error.
The `datastar-fetch` event reaches it for every request on the page, and `evt.detail.el === el`
keeps it to the button's own: the event's `el` is in the 1.0.5 client, as
[Components](/components/#showing-a-change-before-the-server-answers) uses it, and not in
Datastar's documentation.
