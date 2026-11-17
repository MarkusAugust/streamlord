---
title: "Loading indicators"
description: "Show that a request is on its way with data-indicator, the rules it keeps, and one counter for a whole widget."
---

> *A man who goes quiet in the middle of a fight is either dead or thinking. Say which.*
>
> Gorvek of Bonereach

A reader who presses a button and sees nothing presses it again. Datastar's `data-indicator` turns
a signal on while a request is on its way and off when it is over, so the page can say so the
moment the press lands, before the server has answered anything. It keeps three rules that, broken,
fail without a word, one naming habit saves trouble, and a few requests keep it on. Each of them
below was tried in Chrome 157 with Datastar 1.0.5, against a stream the server held open for
twelve seconds and against a plain HTML answer.

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
the answer has been patched in: Datastar reported the start, then the patch, then the end. A
request that ends in an error, or one a newer request [cancelled](#one-name-one-element), is over
too; the few that are not come [further down](#requests-that-keep-it-on).

It follows the requests of its own element and no other: the client skips any request whose
element is not the one the attribute stands on. Put on a `div` around the button, it stayed `false`
while the button's request ran.

The message starts out `hidden`, so it stays out of sight in the moment before Datastar has read
the page, and `data-attr:hidden` takes over from there. Not `data-show`, which sets `display` and
leaves the `hidden` attribute where it is: with both, the message stayed hidden while the request
ran. And not a `style` attribute either, which a Content Security Policy without
`'unsafe-inline'` for styles refuses: on the page
[Security](/security/#a-page-that-really-runs-under-one) shows running under one, a
`style="display: none"` was ignored and the message stood in plain view. If a class on the message
sets `display`, give it `[hidden] { display: none }` too: a class rule outranks the browser's own
rule for `hidden`, and in Chrome a `display: flex` class showed the message despite it.

## One name, one element

Two elements that share an indicator's name turn each other's off. Two buttons with
`data-indicator="orders._loading"` and the same `@get('/orders?page=2')`: the second press
cancelled the first request, as Datastar does for a request with the same method to the same URL
as written in `@get`, and the end of the cancelled one set the signal to `false` while the second
still ran. One element pressed twice stayed `true`, since an element counts its own requests.

So give each element its own name, or, where many controls drive one table, use
[one indicator for the whole widget](#one-indicator-for-a-whole-widget).

## The indicator before data-init

Datastar reads an element's attributes in the order they are written, and the elements of a page
in document order, a parent before its children. The DSL writes attributes in the order you call
it. On an element that sends a request as it appears, the indicator comes first:

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
stayed `false` for that request. The same holds for the listener of the
[counter](#one-indicator-for-a-whole-widget) further down: written after `data-init` on one
element it missed the first request, and written before it counted it.

## A name that stays in the browser

The indicator's signal is the page's own business, and a name with an underscore part keeps it
there. Datastar sends no signal whose name, or any part of it, starts with one: the default filter
excludes `/(^|\.)_/`. A request from a page holding `orders.page` and `orders._loading` sent `page`
and not `_loading`. Without the underscore it goes out with every request: a button with
`data-indicator="orders.loading"` sent `"orders":{"loading":true}`, since the indicator is turned
on before the signals are read. See
[Signals that stay in the browser](/signals/#signals-that-stay-in-the-browser). With the widget's
own path in front, `orders._loading` and `invoices._loading`, two widgets on one page keep two
indicators.

## One indicator for a whole widget

A table with pages, a sort and a size has many controls, and the rule of one name per element
would give each its own indicator. Count instead, on the widget's own element:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun HTMLTag.loadingCounter(name: String) {
    val mark = "evt.detail.el['$name']"
    dataSignals(name, "0")
    dataOnFetch(
        statements(
            "evt.detail.type === '${FetchEventType.STARTED}' && el.contains(evt.detail.el) && " +
                "($mark = ($mark || 0) + 1) && " + increment(name),
            "evt.detail.type === '${FetchEventType.FINISHED}' && $mark > 0 && $mark-- && " +
                signal(name) + " > 0 && " + decrement(name),
        ),
    )
    dataAttr("aria-busy", signal(name) + " > 0 ? 'true' : 'false'")
}

fun FlowContent.ordersWidget() {
    div {
        id = "orders"
        loadingCounter("orders._loading")
        p {
            hidden = true
            dataAttr("hidden", signal("orders._loading") + " === 0")
            +"Loading…"
        }
        div {
            id = "orders-table"
            dataInit(get("/orders?page=1"))
            +"Loading the orders…"
        }
    }
}
```

```kotlin tab="String" group=markup sample=declarations
fun loadingCounterHtml(name: String): String =
    $$"""
    data-signals:$$name="0"
    data-on:datastar-fetch="evt.detail.type === 'started' && el.contains(evt.detail.el) && (evt.detail.el['$$name'] = (evt.detail.el['$$name'] || 0) + 1) && $$$name++;
        evt.detail.type === 'finished' && evt.detail.el['$$name'] > 0 && evt.detail.el['$$name']-- && $$$name > 0 && $$$name--"
    data-attr:aria-busy="$$$name > 0 ? 'true' : 'false'"
    """.trimIndent()

val ordersWidgetHtml =
    $$"""
    <div id="orders" $${loadingCounterHtml("orders._loading")}>
        <p hidden data-attr:hidden="$orders._loading === 0">Loading…</p>
        <div id="orders-table" data-init="@get('/orders?page=1')">Loading the orders…</div>
    </div>
    """.trimIndent()
```

```html tab="Template" group=markup
<div id="orders"
     data-signals:orders._loading="0"
     data-on:datastar-fetch="evt.detail.type === 'started' && el.contains(evt.detail.el) && (evt.detail.el['orders._loading'] = (evt.detail.el['orders._loading'] || 0) + 1) && $orders._loading++;
         evt.detail.type === 'finished' && evt.detail.el['orders._loading'] > 0 && evt.detail.el['orders._loading']-- && $orders._loading > 0 && $orders._loading--"
     data-attr:aria-busy="$orders._loading > 0 ? 'true' : 'false'">
    <p hidden data-attr:hidden="$orders._loading === 0">Loading…</p>
    <div id="orders-table" data-init="@get('/orders?page=1')">Loading the orders…</div>
</div>
```

Datastar sends the [`datastar-fetch`](/components/#showing-a-change-before-the-server-answers)
event on `document` for every request: when it starts, for each patch it brings, and when it is
over. In the listener, `el` is the element the attribute stands on, `#orders`, and
`evt.detail.el` is the element that sent the request. When a request starts from inside the
widget, the listener marks the sender, a count kept on the sending element under the counter's
own name, and adds one; when a marked sender's request is over, it takes the mark and the one
away. Every other event, a patch or a request from elsewhere, changes nothing.

It decides at the start, because by the end the answer may have removed the sender: on the last
page, the answer has no Next button. A counter that asked `el.contains` at the end stayed at 1
there, and this one came back to 0. With a count per sender under its own name, it also held up
where a simpler mark did not:

- a counter inside another, both back to 0 once the inner button's request was over;
- two buttons where the second cancelled the first, and one button whose second press cancelled
  its first, the count above zero until the last request was over;
- a request from a button outside the widget, which left it at zero.

The `> 0` before the count goes down keeps it from going below zero when the widget's own element
was replaced in the middle of a request, with `data-signals` setting the count back to 0.

`aria-busy` is written as `'true'` or `'false'`. Given a boolean, `data-attr` writes an empty string
for `true` and removes the attribute for `false`, and an empty `aria-busy` reads as `false`.

The handler answers the inner `#orders-table` and nothing else, so the counter on `#orders` stays
where it is:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun TagConsumer<*>.ordersPage(page: Int) {
    div {
        id = "orders-table"
        p { +"Page $page" }
        if (page < 3) {
            button {
                dataOnClick(get("/orders?page=${page + 1}"))
                +"Next page"
            }
        }
    }
}
```

```kotlin tab="String" group=markup sample=declarations
fun ordersPageHtml(page: Int): String =
    """<div id="orders-table"><p>Page $page</p>""" +
        (if (page < 3) """<button data-on:click="@get('/orders?page=${page + 1}')">Next page</button>""" else "") +
        "</div>"
```

```html tab="Template" group=markup
<div id="orders-table">
    <p>Page 1</p>
    <button data-on:click="@get('/orders?page=2')">Next page</button>
</div>
```

```kotlin tab="Ktor" group=adapter label="Adapter" sample=ktor-routing
get("/orders") {
    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
    call.respondElements(elements { ordersPage(page) })
}
```

```kotlin tab="Spring" group=adapter sample=spring
@GetMapping("/orders")
fun orders(
    @RequestParam(defaultValue = "1") page: Int,
): ResponseEntity<String> = datastarElements(elements { ordersPage(page) })
```

With the String tab the answer is `ordersPageHtml(page)`; with a template, your engine renders the
same fragment. A stream answer counts the same way and ends when the stream closes.

The counter has to sit on an element the server leaves in place, as `#orders` here. Where an answer
morphs the widget's own element, by the same id in the default mode, write the same three
attributes into the answer: a morph leaves an attribute whose value has not changed, so the
listener stays and the count is not reset in the middle of a request. An answer that replaces the
element outright, with `mode = ElementPatchMode.REPLACE`, starts the count again from its
`data-signals`.

## Requests that keep it on

A few requests start and are not over, and an indicator or a counter stays on with them:

- An answer that is a script, `text/javascript` from `respondScript` in Ktor or `datastarScript`
  in Spring. Datastar runs it and does not end the request; in Chrome the script ran and the
  indicator stayed `true`, until a second press to the same URL cancelled the first request and
  ended it. Send the script in a stream, which ends when the stream closes:

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
  from its own submit instead, where the browser checks it first and sends nothing while it is
  invalid:

```html
<form data-indicator="orders._searching" data-on:submit="@get('/orders', {contentType: 'form'})">
```

A request to a server that cannot be reached is over in the end, but late: Datastar tries again,
with the indicator on, and after eight seconds against a closed port it was still `true`, four
retries in. With the defaults it gives up after ten, some three minutes in by the client's own
schedule, and the indicator goes out then. `{retryMaxCount: 2}` on the action gives up sooner, and
[Components](/components/#showing-a-change-before-the-server-answers) shows how to tell the reader
the request failed.

## A stream that stays open

An indicator is on until its request is over, and a [live view](/live-views/) holds a request that
is never over: an indicator on the element that holds it never goes out. In Chrome, one on the
twelve-second stream stayed `true` until the stream ended. Put the indicator on the elements that
send [commands](/live-views/#commands-go-in-the-view-comes-out), where it covers the command
itself; the change it makes arrives on the stream. Keep the element that holds the stream outside
a widget's counter, or the count never comes back to zero.

## The first view

Before Datastar has read the page there is no signal to show, and the first request starts only
once it has. So the first view says so in its markup, as `#orders-table` does above,
`Loading the orders…`, and the first answer replaces it. The counter counts that first request
too, since `#orders` comes before `#orders-table` in the document: on the first view both
messages show, and for every press after it the counter's.
