---
title: "Loading indicators"
description: "Show that a request is on its way with data-indicator, the rules it keeps, and one counter for a whole widget."
---

> *A man who goes quiet in the middle of a fight is either dead or thinking. Say which.*
>
> Gorvek of Bonereach

A reader who presses a button and sees nothing presses it again. Datastar's `data-indicator` turns
a signal on while a request is on its way and off when it is over, so the page can say so the
moment the press lands, before the server has answered anything. It keeps three rules that fail
without a word, one naming habit saves trouble, and a few requests never let it go out. Each
of them below was tried in Chrome 157 with Datastar 1.0.5, against a stream the server held open
for twelve seconds and against a plain HTML answer.

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
the answer has been patched in: the events came in that order, `started`, the patch, `finished`.
A request that ends in an error, or one a newer request [cancelled](#one-name-one-element), is over
too; the few that never are come [further down](#requests-that-never-end).

It follows the requests of its own element and no other: the client skips any request whose
element is not the one the attribute stands on. Put on a `div` around the button, it stayed `false`
while the button's request ran.

The message starts out `hidden`, so it stays out of sight in the moment before Datastar has read
the page, and `data-attr:hidden` takes over from there. Not `data-show`, which sets `display` and
leaves the `hidden` attribute where it is: with both, the message stayed hidden while the request
ran. And not a `style` attribute either, which a Content Security Policy without
`'unsafe-inline'` for styles refuses: on the page
[Security](/security/#a-page-that-really-runs-under-one) shows running under one, a
`style="display: none"` was ignored and the message stood in plain view.

## One name, one element

Two elements that share an indicator's name turn each other's off. Two buttons with
`data-indicator="orders._loading"` and the same `@get('/orders?page=2')`: the second press
cancelled the first request, as Datastar does for a request to the same URL as written in `@get`,
and the end of the cancelled one set the signal to `false` while the second still ran. One element
pressed twice stayed `true`, since an element counts its own requests.

So give each element its own name, or, where many controls drive one table, use
[one indicator for the whole widget](#one-indicator-for-a-whole-widget).

## Before data-init

Datastar reads an element's attributes in the order they are written, and the DSL writes them in
the order you call it. On an element that sends a request as it appears, the indicator comes first:

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
stayed `false` for that request. The same holds for the counter below: its
`data-on:datastar-fetch` written after `data-init` on one element missed the first request, and
written before it counted it.

## A name that stays in the browser

The indicator's signal is the page's own business, and a name with an underscore part keeps it
there. Datastar sends no signal whose name, or any part of it, starts with one: the default filter
excludes `/(^|\.)_/`. A request from a page holding `orders.page` and `orders._loading` sent `page`
and not `_loading`, so the indicator never reaches the server; see
[Signals that stay in the browser](/signals/#signals-that-stay-in-the-browser). With the widget's
own path in front, `orders._loading` and `invoices._loading`, two widgets on one page keep two
indicators.

## One indicator for a whole widget

A table with pages, a sort and a size has many controls, and the rule of one name per element
would give each its own indicator. Count instead, on the widget's own element:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun HTMLTag.loadingCounter(name: String) {
    val started = "evt.detail.type === '${FetchEventType.STARTED}'"
    val finished = "evt.detail.type === '${FetchEventType.FINISHED}'"
    dataSignals(name, "0")
    dataOnFetch(
        statements(
            "$started && el.contains(evt.detail.el) && (evt.detail.el.__counter = el)",
            "evt.detail.el.__counter === el && (" +
                setExpr(name, "Math.max(0, ${signal(name)} + ($started ? 1 : $finished ? -1 : 0))") + ")",
        ),
    )
    dataAttr("aria-busy", "${signal(name)} > 0 ? 'true' : 'false'")
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
val ordersWidgetHtml =
    $$"""
    <div id="orders" data-signals:orders._loading="0" data-on:datastar-fetch="evt.detail.type === 'started' && el.contains(evt.detail.el) && (evt.detail.el.__counter = el); evt.detail.el.__counter === el && ($orders._loading = Math.max(0, $orders._loading + (evt.detail.type === 'started' ? 1 : evt.detail.type === 'finished' ? -1 : 0)))" data-attr:aria-busy="$orders._loading > 0 ? 'true' : 'false'">
        <p hidden data-attr:hidden="$orders._loading === 0">Loading…</p>
        <div id="orders-table" data-init="@get('/orders?page=1')">Loading the orders…</div>
    </div>
    """.trimIndent()

fun ordersPageHtml(page: Int): String =
    """<div id="orders-table"><p>Page $page</p>""" +
        (if (page < 3) """<button data-on:click="@get('/orders?page=${page + 1}')">Next page</button>""" else "") +
        "</div>"
```

```html tab="Template" group=markup
<div id="orders" data-signals:orders._loading="0" data-on:datastar-fetch="evt.detail.type === 'started' && el.contains(evt.detail.el) && (evt.detail.el.__counter = el); evt.detail.el.__counter === el && ($orders._loading = Math.max(0, $orders._loading + (evt.detail.type === 'started' ? 1 : evt.detail.type === 'finished' ? -1 : 0)))" data-attr:aria-busy="$orders._loading > 0 ? 'true' : 'false'">
    <p hidden data-attr:hidden="$orders._loading === 0">Loading…</p>
    <div id="orders-table" data-init="@get('/orders?page=1')">Loading the orders…</div>
</div>
```

The handler answers the inner `#orders-table` and nothing else, so the counter on `#orders` stays
where it is:

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

With the String tab, the answer is `ordersPageHtml(page)` in either; with a template, render the
same markup with your engine.

Datastar sends `datastar-fetch` on `document` for every request, with the element that sent it, so
the listener on `#orders` hears them all. When a request starts from inside the widget, it marks
the sending element and adds one; when a marked element's request ends, it takes one away. It
decides at the start, because by the end the answer may have removed the sender: on the last
page, the answer has no Next button. A counter that asked `el.contains` at the end stayed at 1
there, and this one came back to 0.

With two buttons inside the widget, the second cancelling the first, the count stayed above zero
until the second was over, and a request from a button outside the widget left it at zero. A
stream answer counts the same way and ends when the stream closes.

`aria-busy` is written as `'true'` or `'false'`. Given a boolean, `data-attr` writes an empty string
for `true` and removes the attribute for `false`, and an empty `aria-busy` reads as `false`.

The counter has to sit on an element the server leaves in place, as `#orders` here. Where an answer
replaces the widget's own element, write the same three attributes into the answer: a morph leaves
an attribute whose value has not changed, so the listener stays and the count is not reset in the
middle of a request.

## Requests that never end

A few requests start and are never over, and their indicator stays on:

- An answer that is a script, `text/javascript` from `respondScript` in Ktor or `datastarScript`
  in Spring. Datastar runs it and never ends the request. In Chrome the script ran and the
  indicator stayed `true`. Send the script in a stream instead, `executeScript` inside
  `respondDatastar { }`, which ends when the stream closes.
- A button that sends a form, `@get('/orders', {contentType: 'form'})`, while the form fails its
  validation. Datastar reports the start and nothing after it. Send the form from its own
  `data-on:submit` instead: the browser checks it first and sends nothing while it is invalid.
- A request the server cannot be reached for. Datastar tries again, with the indicator on: after
  eight seconds against a closed port it was still `true`, four retries in. With the defaults it
  gives up after ten, some three minutes in by the client's own schedule.

## A stream that stays open

An indicator is on until its request is over, and a [live view](/live-views/) holds a request that
is never over: an indicator on the element that holds it never goes out. In Chrome, one on the
twelve-second stream stayed `true` until the stream ended. Put the indicator on the elements that
send [commands](/live-views/#commands-go-in-the-view-comes-out), where it covers the command
itself; the change it makes arrives on the stream. Keep the element that holds the stream outside
a widget's counter, or the count never comes back to zero.

## The first view

Before Datastar has read the page there is no signal to show, and the first request starts only
once it has. So the waiting text of the first view is part of the markup, as in `#orders-table`
above, `Loading the orders…`, and the first answer replaces it. The counter's own message waits,
`hidden`, for the presses after that.
