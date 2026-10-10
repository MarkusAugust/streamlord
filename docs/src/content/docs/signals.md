---
title: "Signals and codecs"
description: "Reading the browser's store as a map, or as your own data class."
---

Signals are Datastar's client-side store: a small reactive object the browser keeps, sends back
with requests, and updates when you patch it. On the server they arrive as JSON, and you decide
how much structure you want out of them.

## Where they come from

The protocol decides, and the adapters follow it: the `datastar` query parameter for `GET` and
`DELETE`, the request body for `POST`, `PUT`, `PATCH` and `QUERY`. You never write that rule
down yourself.

## Without a codec

The built-in reader needs no dependency and no data class:

```kotlin sample=ktor-routing
get("/page") {
    val signals = call.readSignals()

    val query = signals.string("search") ?: ""
    val page = signals.int("page") ?: 1
    val advanced = signals.boolean("advanced") ?: false
    val city = signals.path("address", "city")
}
```

`Signals` has `string`, `int`, `long`, `double`, `decimal` for an exact `BigDecimal`, `boolean`,
`obj` for a nested object, `array` for a nested list, `has` to ask whether a key is there at all,
and `path` for reaching through several levels. Every accessor returns null rather than throwing,
because a signal the browser never set is a normal thing and not an error.

That is the whole of it, and for many endpoints it is enough. Reading two fields out of a request
does not need a class.

## With a codec

When the shape is worth naming, it takes three things: a codec on the instance, a class, and a
read into that class.

**One.** The codec, installed once for the application. The built-in reader cannot build a data
class, so this is what makes the rest of this section possible:

```kotlin sample=ktor-application
install(StreamlordPlugin) { codec = KotlinxSignalsCodec() }
```

**Two.** The class, which names the signals this handler accepts. It is an ordinary
`@Serializable` data class and nothing about it is Streamlord's:

```kotlin sample=declarations
@Serializable
data class SearchSignals(val query: String = "", val page: Int = 1)
```

**Three.** The read, which hands that class to the codec from step one:

```kotlin sample=ktor-routing
post("/search") {
    val signals = call.readSignalsOr(SearchSignals())
    val hits = repository.search(signals.query)
}
```

`signals` is a `SearchSignals` now, so `signals.query` is a `String` the compiler knows about,
and the JSON the browser sent never appears in your code.

`readSignals<T>()` returns null when the request carried no signals at all; `readSignalsOr(default)`
saves you the elvis. Defaults on the data class matter more than usual here, because the browser
sends only the signals it has: a page that never set `page` sends no `page`, and the default is
what the handler gets.

## The type is the input surface

A page carries every signal it has ever set, and the browser sends the whole store back on every
request, after the reader has had every chance to edit it. A signal arriving at a handler is a
request body from a stranger.

The type you read into is where you decide what this handler accepts. It is a narrowing, not a
registry: a page may carry fifteen signals while a handler that names two sees two, because the
codecs ignore keys the type does not name. Declare it per handler rather than once for the
application, and the handler documents its own input at the point of use.

An unread signal is harmless by construction. The danger is only ever a signal that is read and
then trusted, which is why the type is worth keeping small.

The failure that is not harmless is the opposite one: a signal a handler reads that no page ever
declares. That compiles, deploys, and hands the handler a default on every request. `signalDrift`
in `streamlord-analysis` compares the two sets across your whole project and reports it, which is
on [Testing](/testing/).

If you also want it to fail at request time, that is your codec's own setting rather than
something Streamlord adds: `KotlinxSignalsCodec(json = Json { ignoreUnknownKeys = false })`
refuses a body carrying anything the type does not name. It is worth a test and a poor fit for
production, because it makes every handler fail the moment any page gains a signal.

## Which codec

| Module | Codec | Use it when |
|---|---|---|
| `streamlord-json-kotlinx` | `KotlinxSignalsCodec` | your application already uses kotlinx.serialization |
| `streamlord-json-jackson` | `JacksonSignalsCodec` | Spring Boot 4, which ships Jackson 3 (`tools.jackson`) |
| `streamlord-json-jackson2` | `Jackson2SignalsCodec` | Spring Boot 3, which ships Jackson 2 (`com.fasterxml`) |
| none | the built-in reader | you are happy reading by name |

The two Jackson modules exist because Jackson 3 moved package. Taking the wrong one compiles and
then fails at runtime on an `ObjectMapper` that is not the one Spring configured.

## In a native image

A codec finds the serializer for a `KType` by reading the class, which GraalVM's native-image
cannot see. Name the serializers instead:

```kotlin sample=statements
val codec = KotlinxSignalsCodec(
    serializers = mapOf(typeOf<SearchSignals>() to SearchSignals.serializer()),
)
```

Types you leave out still resolve themselves, so this costs nothing on the JVM. The rest of what
an image needs (the build file, the resources, the container) is on [Native image](/native-image/).

## Patching signals back

The same store, in the other direction:

```kotlin sample=ktor-routing
get("/feed") {
    call.respondDatastar {
        patchSignals("total" to 13, "draft" to null)
    }
}
```

A signal patched to `null` is **removed**, because signal patches are RFC 7386 merge patches. That
is not a quirk of Streamlord; it is what the protocol says, and it is how you delete.

## Signals that stay in the browser

Not every signal is for the server. Whether a drawer is showing, which row the cursor is on, a
draft the reader has not sent yet: these are state the page needs and no handler reads.

Start the name with an underscore and it stays behind. Every action sends the store through a
filter whose default excludes `/(^|\.)_/`, so `_drawerOpen` never travels, and neither does
`form._draft`:

```kotlin sample=html
div {
    dataSignals("_drawerOpen" to false, "query" to "")
    button {
        dataOnClick(toggle("_drawerOpen"))
        +"Menu"
    }
    input { dataBind("query") }
    button {
        dataOnClick(post("/search"))
        +"Search"
    }
}
```

The `@post` carries `query` and nothing else. A handler cannot trust what was never sent, so an
underscore is also the cheapest way to keep a request small and its input surface smaller.

Two options narrow it further, per action. `filterSignals` picks from the store by name, and
`payloadExpr` sends a value of your own instead of the store:

```kotlin sample=html
div {
    button {
        dataOnClick(post("/cart") { filterSignals = SignalFilter.include("^cart\\.") })
        +"Save cart"
    }
    button {
        dataOnClick(post("/rows/delete") { payloadExpr = "{ids: \$selected}" })
        +"Delete"
    }
}
```

The client fills each half of the filter on its own: set only `include`, and the default
`exclude` still keeps the underscored names home. `payloadExpr` sends exactly the expression's
value, so `readSignals()` on `/rows/delete` sees `ids` and nothing else.

## The size limit

Incoming signals are capped at 1 MiB by default, and the cap is applied *while reading*. A
declared `Content-Length` above the limit is rejected before a byte is read; a chunked body is
cut off one byte past it. Nothing larger than the cap ever sits in memory. Map
`SignalsTooLargeException` to `413` in your framework's error handling, and a body that will not
parse or decode to `400`. See [Security](/security/).
