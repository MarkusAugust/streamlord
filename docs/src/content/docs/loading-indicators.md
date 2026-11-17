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

A table has many controls. Datastar's how-to on
[keeping code DRY](https://data-star.dev/how_tos/keep_datastar_code_dry) shows one listener on the
element around many buttons, since a click bubbles up to it. This page takes that one step on: the
element around the controls sends every request of the widget, the controls only set signals, and
one indicator on that element covers it all:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
val loadOrders =
    get("/orders") {
        filterSignals = SignalFilter.include("^orders\\.(page|sort|size)$")
        retryMaxCount = 2
    }

fun FlowContent.ordersWidget() {
    div {
        id = "orders"
        dataSignals("{orders: {page: 1, sort: 'date', size: 10, _unreachable: false}}")
        dataIndicator("orders._loading")
        dataAttr("aria-busy", signal("orders._loading") + " ? 'true' : 'false'")
        dataOnClick("!evt.defaultPrevented && evt.target.closest('button') && " + loadOrders)
        dataOnChange("evt.target.closest('select') && " + loadOrders)
        dataOnFetch(
            "evt.detail.el === el && ['started', 'retries-failed'].includes(evt.detail.type) && (" +
                setExpr("orders._unreachable", "evt.detail.type === 'retries-failed'") + ")",
        )
        dataInit(loadOrders)
        p {
            hidden = true
            dataAttr("hidden", "!" + signal("orders._loading"))
            +"Loading…"
        }
        p {
            hidden = true
            dataAttr("hidden", "!" + signal("orders._unreachable"))
            +"The orders could not be reached. Press any button to try again."
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
         data-signals="{orders: {page: 1, sort: 'date', size: 10, _unreachable: false}}"
         data-indicator="orders._loading"
         data-attr:aria-busy="$orders._loading ? 'true' : 'false'"
         data-on:click="!evt.defaultPrevented && evt.target.closest('button') && @get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}, retryMaxCount: 2})"
         data-on:change="evt.target.closest('select') && @get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}, retryMaxCount: 2})"
         data-on:datastar-fetch="evt.detail.el === el && ['started', 'retries-failed'].includes(evt.detail.type) && ($orders._unreachable = evt.detail.type === 'retries-failed')"
         data-init="@get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}, retryMaxCount: 2})">
        <p hidden data-attr:hidden="!$orders._loading">Loading…</p>
        <p hidden data-attr:hidden="!$orders._unreachable">The orders could not be reached. Press any button to try again.</p>
        <div id="orders-table">Loading the orders…</div>
    </div>
    """.trimIndent()
```

```html tab="Template" group=markup
<div id="orders"
     data-signals="{orders: {page: 1, sort: 'date', size: 10, _unreachable: false}}"
     data-indicator="orders._loading"
     data-attr:aria-busy="$orders._loading ? 'true' : 'false'"
     data-on:click="!evt.defaultPrevented && evt.target.closest('button') && @get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}, retryMaxCount: 2})"
     data-on:change="evt.target.closest('select') && @get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}, retryMaxCount: 2})"
     data-on:datastar-fetch="evt.detail.el === el && ['started', 'retries-failed'].includes(evt.detail.type) && ($orders._unreachable = evt.detail.type === 'retries-failed')"
     data-init="@get('/orders', {filterSignals: {include: /^orders\.(page|sort|size)$/}, retryMaxCount: 2})">
    <p hidden data-attr:hidden="!$orders._loading">Loading…</p>
    <p hidden data-attr:hidden="!$orders._unreachable">The orders could not be reached. Press any button to try again.</p>
    <div id="orders-table">Loading the orders…</div>
</div>
```

The messages start out `hidden`, and `data-attr:hidden` shows them;
[What data-indicator follows](#what-data-indicator-follows) says why not `data-show`. The table's
controls set signals and send nothing themselves, and the answer is the table:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun TagConsumer<*>.ordersTable(
    rows: List<Order>,
    page: Int,
    lastPage: Int,
    sort: String,
    size: Int,
) {
    div {
        id = "orders-table"
        button {
            attributes["aria-pressed"] = (sort == "who").toString()
            dataOnClick(statements(set("orders.sort", "who"), set("orders.page", 1)))
            +"Sort by who"
        }
        select {
            dataBind("orders.size")
            dataOnChange(set("orders.page", 1))
            for (n in listOf(10, 25)) {
                option {
                    selected = n == size
                    +"$n"
                }
            }
        }
        table {
            tbody {
                for (row in rows) {
                    tr {
                        td { +"${row.id}" }
                        td { +row.who }
                        td { +row.what }
                    }
                }
            }
        }
        p { +"Page $page of $lastPage" }
        if (page > 1) {
            button {
                dataOnClick(set("orders.page", page - 1))
                +"Previous page"
            }
        }
        if (page < lastPage) {
            button {
                dataOnClick(set("orders.page", page + 1))
                +"Next page"
            }
        }
    }
}
```

```kotlin tab="String" group=markup sample=declarations
fun ordersTableHtml(
    rows: List<Order>,
    page: Int,
    lastPage: Int,
    sort: String,
    size: Int,
): Html {
    val body = rows.map { interpolate { """<tr><td>${+it.id}</td><td>${+it.who}</td><td>${+it.what}</td></tr>""" } }.joinToHtml()
    val sizes =
        listOf(10, 25)
            .map { interpolate { if (it == size) """<option selected>${+it}</option>""" else """<option>${+it}</option>""" } }
            .joinToHtml()
    val previous = interpolate { $$"""<button data-on:click="$orders.page = $${+(page - 1)}">Previous page</button>""" }
    val next = interpolate { $$"""<button data-on:click="$orders.page = $${+(page + 1)}">Next page</button>""" }
    val buttons = listOfNotNull(previous.takeIf { page > 1 }, next.takeIf { page < lastPage }).joinToHtml()
    return interpolate {
        $$"""
        <div id="orders-table">
            <button aria-pressed="$${+(sort == "who")}" data-on:click="$orders.sort = 'who'; $orders.page = 1">Sort by who</button>
            <select data-bind="orders.size" data-on:change="$orders.page = 1">$${+sizes}</select>
            <table><tbody>$${+body}</tbody></table>
            <p>Page $${+page} of $${+lastPage}</p>$${+buttons}
        </div>
        """.trimIndent()
    }
}
```

```html tab="Template" group=markup
<div id="orders-table">
    <button aria-pressed="{{ sort == 'who' }}" data-on:click="$orders.sort = 'who'; $orders.page = 1">Sort by who</button>
    <select data-bind="orders.size" data-on:change="$orders.page = 1">
        {% for n in [10, 25] %}<option{% if n == size %} selected{% endif %}>{{ n }}</option>{% endfor %}
    </select>
    <table><tbody>
        {% for row in rows %}<tr><td>{{ row.id }}</td><td>{{ row.who }}</td><td>{{ row.what }}</td></tr>{% endfor %}
    </tbody></table>
    <p>Page {{ page }} of {{ lastPage }}</p>
    {% if page > 1 %}<button data-page="{{ page - 1 }}" data-on:click="$orders.page = Number(el.dataset.page)">Previous page</button>{% endif %}
    {% if page < lastPage %}<button data-page="{{ page + 1 }}" data-on:click="$orders.page = Number(el.dataset.page)">Next page</button>{% endif %}
</div>
```

```kotlin tab="Ktor" group=adapter label="Adapter" sample=ktor-routing
get("/orders") {
    val signals = call.readSignals().obj("orders")
    val sort = signals?.string("sort") ?: "date"
    val size = signals?.int("size") ?: 10
    val lastPage = orderBook.lastPage(size)
    val page = (signals?.int("page") ?: 1).coerceIn(1, lastPage)
    call.respondElements(elements { ordersTable(orderBook.page(page, sort, size), page, lastPage, sort, size) })
}
```

```kotlin tab="Spring" group=adapter sample=spring
@GetMapping("/orders")
fun orders(request: HttpServletRequest): ResponseEntity<String> {
    val signals = request.readSignals().obj("orders")
    val sort = signals?.string("sort") ?: "date"
    val size = signals?.int("size") ?: 10
    val lastPage = orderBook.lastPage(size)
    val page = (signals?.int("page") ?: 1).coerceIn(1, lastPage)
    return datastarElements(elements { ordersTable(orderBook.page(page, sort, size), page, lastPage, sort, size) })
}
```

With the String tab, the answer is `call.respondElements(ordersTableHtml(...))` or
`datastarElements(ordersTableHtml(...))`. It builds the rows with `interpolate`, which escapes
what the database holds, as [Strings](/strings/#values-from-outside) explains. In the Template tab
the engine escapes `{{ }}`, and a value a button sets is read from an attribute,
`Number(el.dataset.page)`, rather than written into the expression, as
[Templates](/templates/#do-not-render-a-value-into-an-expression) explains. A page that no longer
exists, after the size changed, is brought back into range with `coerceIn`; to tell the page so,
put an element that sets `$orders.page` in the answer, as
[Signals and codecs](/signals/#signals-in-a-plain-answer) shows.

### How it works

A press on Next page runs the button's own `data-on:click` first, which sets `$orders.page`, and
then the click reaches `#orders`, which sends the request. A change of the size works the same
through `data-on:change`, after the `select` has set its signal. In Chrome, Next sent page 2,
Sort by who sent the sort with page 1, and a size of 25 went out as the number 25. Every request
of the widget comes from `#orders`, so its one `data-indicator` counts them all: a press while
another request was on its way cancelled the older one, and the indicator stayed `true` until the
newer one was over.

That cancelling is why the page travels in a signal and not in the URL written in `@get`. Datastar
cancels a request with the same method to the same URL as written, before it adds the signals to
it as `datastar=`. `@get('/orders?page=3')` and `@get('/orders?page=2')` are two URLs as written,
both run, and the slower answer lands last.

`filterSignals` sends the signals the server reads and no others, the way
[Caching](/caching/#which-signals-go-in-the-url) explains. A signal you add, a sort direction say,
goes into the filter as well, or the server never hears it. The underscored signals,
`orders._loading` and `orders._unreachable`, stay in the browser in any case;
[further down](#a-name-that-stays-in-the-browser) is why.

The first view is the `data-init`, written after the `data-indicator` on the same element, so the
message shows for it as it does for every press after. Before Datastar has read the page there is
no signal at all, which is why `#orders-table` holds `Loading the orders…` as plain text until the
first answer replaces it. `aria-busy` tells a screen reader the same, on `#orders`, which the
answers leave in place.

### Controls that need more

- **A button with an action of its own**, such as a row's delete, would also fire the widget's
  request. Write its click with `data-on:click__prevent`, `dataOnClick(...) { prevent = true }` in
  the DSL. The widget's click starts with `!evt.defaultPrevented &&` for this: in Chrome it sent
  nothing for such a button, and a menu elsewhere on the page that closes with
  `data-on:click__outside` still closed, which `__stop` would have prevented. Give the button its
  own indicator, `orders._deleting1001`, since it sends its own request, and answer its `@post`
  with the table, so the reader sees the row gone.
- **A button inside a `<form>`** submits the form as well, and the browser leaves the page. Give it
  `type="button"`.
- **A widget inside another**: both hear the click, and in Chrome both sent. Give each widget's
  element a class, `widget`, and start each of its listeners, the click and the change, with
  `evt.target.closest('.widget') === el &&`: the click becomes
  `!evt.defaultPrevented && evt.target.closest('.widget') === el && evt.target.closest('button') && @get(...)`,
  and then only the nearest widget sent.
- **Two widgets on one page** with the same URL cancel each other's requests, since Datastar
  cancels by method and URL "regardless of which element initiated it". Give each its own URL, as
  `/orders` and `/invoices`, or a part that tells them apart, `/orders?list=open`.

Both editors warn of a `data-indicator` written after a `data-init` that sends a request on the
same element, with a fix that moves it first, and of a `data-indicator` on an element that sends
nothing.

## A request that fails

A request whose answer is an error, a 404 or a 500, is over, and the indicator goes out, but the
1.0.5 client reads the body of a `200` and of nothing else, so the table stays as it was and says
nothing. Answer a failure the widget can show as a `200`, with the table's id and a button, which
the widget already sends for, and with `Cache-Control: no-store`, so that no cache keeps the failure
as the page:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun TagConsumer<*>.ordersFailed() {
    div {
        id = "orders-table"
        p { +"The orders could not be read." }
        button { +"Try again" }
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val ordersFailedHtml =
    """<div id="orders-table"><p>The orders could not be read.</p><button>Try again</button></div>"""
```

```html tab="Template" group=markup
<div id="orders-table"><p>The orders could not be read.</p><button>Try again</button></div>
```

```kotlin tab="Ktor" group=adapter label="Adapter" sample=ktor-routing
get("/orders") {
    val page = call.readSignals().obj("orders")?.int("page") ?: 1
    try {
        val rows = orderBook.page(page, "date", 10)
        call.respondElements(elements { ordersTable(rows, page, orderBook.lastPage(10), "date", 10) })
    } catch (e: java.sql.SQLException) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondElements(elements { ordersFailed() })
    }
}
```

```kotlin tab="Spring" group=adapter sample=spring
@GetMapping("/orders")
fun ordersOrFailure(
    request: HttpServletRequest,
    response: HttpServletResponse,
): ResponseEntity<String> {
    val page = request.readSignals().obj("orders")?.int("page") ?: 1
    return try {
        val rows = orderBook.page(page, "date", 10)
        datastarElements(elements { ordersTable(rows, page, orderBook.lastPage(10), "date", 10) })
    } catch (e: java.sql.SQLException) {
        response.setHeader("Cache-Control", "no-store")
        datastarElements(elements { ordersFailed() })
    }
}
```

The `catch` names the failure of the query, here `SQLException`, and nothing wider, so a
cancelled request still ends as Ktor and Spring mean it to.

A server that cannot be reached gives no answer to show. Datastar tries again with the indicator
on, and the defaults the reference gives, a second at first, doubling, at most thirty, ten tries,
come to some three minutes; in Chrome, after eight seconds against a closed port it was still
`true`, with four `retrying` events behind it. `retryMaxCount = 2` in `loadOrders`,
`retryMaxCount: 2` in markup, gave up after three seconds. When it gives up, Datastar sends
`retries-failed`, and the widget's `data-on:datastar-fetch` sets `$orders._unreachable`, which
shows the message; the next request from the widget hides it again. `evt.detail.el === el` keeps
the listener to the widget's own requests: the event's `el` is in the 1.0.5 client, not in
Datastar's documentation.

## What data-indicator follows

The widget rests on four rules, and each holds for any `data-indicator`. The first is what it
follows:

```kotlin tab="kotlinx.html DSL" group=markup label="Markup style" sample=declarations
fun FlowContent.archiveOrders() {
    button {
        dataIndicator("orders._archiving")
        dataOnClick(post("/orders/archive"))
        +"Archive"
    }
    p {
        hidden = true
        dataAttr("hidden", "!" + signal("orders._archiving"))
        +"Archiving…"
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val archiveOrdersHtml =
    $$"""
    <button data-indicator="orders._archiving" data-on:click="@post('/orders/archive')">Archive</button>
    <p hidden data-attr:hidden="!$orders._archiving">Archiving…</p>
    """.trimIndent()
```

```html tab="Template" group=markup
<button data-indicator="orders._archiving" data-on:click="@post('/orders/archive')">Archive</button>
<p hidden data-attr:hidden="!$orders._archiving">Archiving…</p>
```

The signal is `false` until the button's request starts, `true` while it runs, and `false` again
when it is over. For a stream, over means the stream closed. For one HTML or JSON answer, it means
the answer has been patched in: the start came first, then the patch, then the end. A request that
ends in an error, or one a newer request cancelled, is over too.

It follows the requests of its own element and no other. Datastar's reference says only that the
signal is true "while a fetch request is in flight"; the 1.0.5 client skips any request whose
element is not the one the attribute stands on. In Chrome, one put on a `div` around the button
stayed `false` while the button's request ran. That is why the widget lets one element send.

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
`data-indicator="orders._archiving"` and the same `@post('/orders/archive')`: the second press
cancelled the first request, as Datastar does for a request with the same method to the same URL
as written, and the end of the cancelled one set the signal to `false` while the second still ran.
One element pressed twice stayed `true`, since an element counts its own requests. Give each element
its own name, or let one element send, as the widget does.

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
stayed `false` for that request. Both editors warn of it, with a fix that moves the indicator first.

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

### An answer that is a script

`text/javascript`, from `respondScript` in Ktor or `datastarScript` in Spring: Datastar runs it and
does not end the request. In Chrome the script ran and the indicator stayed `true`. A second press
with the same method to the same URL ends the first request, but if it is answered with a script as
well, it hangs in its turn. Send the script in a stream, which ends when the stream closes:

```kotlin tab="Ktor" group=adapter label="Adapter" sample=ktor-routing
post("/orders/print") {
    call.respondDatastar { executeScript("window.print()") }
}
```

```kotlin tab="Spring" group=adapter sample=spring
@PostMapping("/orders/print")
fun print(response: HttpServletResponse): org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody =
    response.datastarStream { executeScript("window.print()") }
```

### A form sent from a button

`@get('/orders', {contentType: 'form'})` on a button, while the form fails its validation: Datastar
reports the start and nothing after it, and nothing ends it. In Chrome the button's indicator stayed
`true` for good, even after a later valid press from the same button had come back. Send the form
from its own submit, as the second form of Datastar's
[form example](https://data-star.dev/examples/form_data) does: the browser checks it first and sends
nothing while it is invalid.

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
        dataOnClick(statements("document.getElementById('tally')?.classList.add('waiting')", post("/tally/increment")))
        dataOnFetch(
            "evt.detail.el === el && " +
                "['${FetchEventType.ERROR}', '${FetchEventType.RETRIES_FAILED}'].includes(evt.detail.type) && " +
                "document.getElementById('tally')?.classList.remove('waiting')",
        )
        +"One more"
    }
}
```

```kotlin tab="String" group=markup sample=declarations
val tallyCommandHtml =
    """
    <button data-on:click="document.getElementById('tally')?.classList.add('waiting'); @post('/tally/increment')"
            data-on:datastar-fetch="evt.detail.el === el && ['error', 'retries-failed'].includes(evt.detail.type) && document.getElementById('tally')?.classList.remove('waiting')">One more</button>
    """.trimIndent()
```

```html tab="Template" group=markup
<button data-on:click="document.getElementById('tally')?.classList.add('waiting'); @post('/tally/increment')"
        data-on:datastar-fetch="evt.detail.el === el && ['error', 'retries-failed'].includes(evt.detail.type) && document.getElementById('tally')?.classList.remove('waiting')">One more</button>
```

```css
#tally.waiting { opacity: 0.5; }
```

The class goes on `#tally`, the element the stream renders, and not on `#view`, which the stream
only fills: its patch, `INNER`, leaves `#view`'s own class alone. The stream renders `#tally`
without the class, and the morph takes it off: in Chrome, the class went on with the press and came
off when the new tally arrived. It is a class and not a signal for that reason: the morph clears a
class the server does not render, and leaves a signal as it was. The `?.` matters before the first
view: until the stream has sent it there is no `#tally`, and without it a press threw and sent no
command.

It holds only for a command that always changes what the view shows. A live view sends nothing
when a render is the same as the last, so a command that changes nothing, a Next on the last
order, would leave the class on. Where a command can do that, put a `data-indicator` on the command
instead, which covers the command and not the change.

A command that fails sends no new view, so the button's listener takes the class off on an error.
The `datastar-fetch` event reaches it for every request on the page, and `evt.detail.el === el`
keeps it to the button's own: the event's `el` is in the 1.0.5 client, as
[Components](/components/#showing-a-change-before-the-server-answers) uses it, and not in
Datastar's documentation.
