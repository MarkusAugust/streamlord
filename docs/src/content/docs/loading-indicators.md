---
title: "Loading indicators"
description: "Show that a request is on its way with data-indicator, the four rules it keeps, and one counter for a whole widget."
---

> *A man who goes quiet in the middle of a fight is either dead or thinking. Say which.*
>
> Gorvek of Bonereach

A reader who presses a button and sees nothing presses it again. Datastar's `data-indicator` turns
a signal on while a request is on its way and off when it is over, so the page can say so the
moment the press lands, before the server has answered anything. It keeps four rules that are easy
to miss and fail without a word. Each one below was tried in Chrome 157 with Datastar 1.0.5,
against a stream the server held open for twelve seconds.

## What data-indicator follows

```html
<button data-indicator="orders._loading" data-on:click="@get('/orders')">Next page</button>
<p data-show="$orders._loading" style="display: none">Loading…</p>
```

The signal is `false` until a request starts, `true` while it runs, and `false` again when it is
over, however it ended: an answer, an error, or a request a newer one cancelled. For a stream,
over means the stream closed.

It follows the requests of its own element and no other: the client skips any request whose element
is not the one the attribute stands on. Put on a `div` around the button, it stayed `false` while
the button's request ran. The `style="display: none"` keeps the message hidden in the moment
before Datastar has read the page.

## One name, one element

Two elements that share an indicator's name turn each other's off. Two buttons with
`data-indicator="orders._loading"` and the same `@get('/orders')`: the second press cancelled the
first request, as Datastar does for a request to the same URL as written in `@get`, and the end
of the cancelled one set the signal to `false` while the second still ran. One element pressed
twice stayed `true`, since an element counts its own requests.

So give each element its own name, or, where many controls drive one table, use
[one indicator for the whole widget](#one-indicator-for-a-whole-widget).

## Before data-init

Datastar reads an element's attributes in the order they are written. On an element that sends a
request as it appears, the indicator has to come first:

```html
<div data-indicator="orders._loading" data-init="@get('/orders')"></div>
```

Written the other way round, the request was on its way before the indicator listened, and it
stayed `false` for that request. The same holds for the counter below and its `data-on`.

## A name that stays in the browser

`orders._loading` is a name with an underscore part, and Datastar sends no signal whose name, or
any part of it, starts with one: the default filter excludes `/(^|\.)_/`. A request from a page
holding `orders.page` and `orders._loading` sent `page` and not `_loading`. The server reads no
indicator it never asked for, and the signals stay under the size limit; see
[Signals that stay in the browser](/signals/#signals-that-stay-in-the-browser). Put the widget's
own path in front, `orders._loading` and `invoices._loading`, and two widgets on one page keep
two indicators.

## One indicator for a whole widget

A table with pages, a sort and a size has many controls, and each would need its own indicator.
Count instead: the widget's own element listens to `datastar-fetch`, the event Datastar sends for
every request with the element that sent it, and adds one when a request inside the widget starts
and takes one away when it ends.

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun HTMLTag.loadingCounter(name: String) {
    val count = signal(name)
    dataSignals(name, "0")
    dataOn(
        "datastar-fetch",
        "el.contains(evt.detail.el) && ($count = Math.max(0, $count + " +
            "(evt.detail.type === 'started' ? 1 : evt.detail.type === 'finished' ? -1 : 0)))",
    )
    dataAttr("aria-busy", "$count > 0 ? 'true' : 'false'")
}

fun FlowContent.ordersPanel() {
    div {
        id = "orders"
        loadingCounter("orders._loading")
        p {
            dataShow(signal("orders._loading") + " > 0")
            style = "display: none"
            +"Loading…"
        }
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val ordersWidget =
    $$"""
    <div id="orders"
         data-signals:orders._loading="0"
         data-on:datastar-fetch="el.contains(evt.detail.el) && ($orders._loading = Math.max(0, $orders._loading + (evt.detail.type === 'started' ? 1 : evt.detail.type === 'finished' ? -1 : 0)))"
         data-attr:aria-busy="$orders._loading > 0 ? 'true' : 'false'">
        <p data-show="$orders._loading > 0" style="display: none">Loading…</p>
    </div>
    """.trimIndent()
```

```html tab="Template" group=markup
<div id="orders"
     data-signals:orders._loading="0"
     data-on:datastar-fetch="el.contains(evt.detail.el) && ($orders._loading = Math.max(0, $orders._loading + (evt.detail.type === 'started' ? 1 : evt.detail.type === 'finished' ? -1 : 0)))"
     data-attr:aria-busy="$orders._loading > 0 ? 'true' : 'false'">
    <p data-show="$orders._loading > 0" style="display: none">Loading…</p>
</div>
```

With two buttons inside the widget, the second cancelling the first, the counter stayed above
zero until the second was over, and a request from a button outside the widget left it at zero.
Each request adds one as it starts and takes one away as it ends, so a cancelled request takes
back only its own.

`aria-busy` is written as `'true'` or `'false'`. An expression that gives a boolean sets the
attribute to an empty string, which a screen reader takes for `false`.

The counter has to sit on an element the server leaves in place. When an answer replaces the
widget's own element, write the same three attributes into that answer: a morph leaves an
attribute whose value has not changed, so the listener stays and the count is not reset in the
middle of a request. If the widget sends its first request from the same element with
`data-init`, write `data-on:datastar-fetch` before it, as above.

## A stream that stays open

An indicator is on until its request is over, and a [live view](/live-views/) is a request that
is never over: an indicator on the element that holds it never goes out. In Chrome, one on the
twelve-second stream stayed `true` until the stream ended. Put the indicator on the elements that
send commands, and keep the element that holds the stream outside a widget's counter, or the
count never comes back to zero.

## The first view

Before Datastar has read the page there is no signal to show, and the first request starts only
once it has. A widget whose content arrives with that request shows its waiting state in the markup
itself: render the `Loading…` text as part of the page, not behind `data-show`, and let the first
answer replace it.
