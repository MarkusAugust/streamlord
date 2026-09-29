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

When the shape is worth naming, configure a codec and read into it:

```kotlin sample=declarations
@Serializable
data class Search(val query: String = "", val page: Int = 1)
```

```kotlin sample=ktor-routing
post("/search") {
    val signals = call.readSignalsOr(SearchSignals())
    val hits = repository.search(signals.query)
}
```

`readSignals<T>()` returns null when the request carried no signals at all; `readSignalsOr(default)`
saves you the elvis. Defaults on the data class matter more than usual here, because the browser
sends only the signals it has.

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

## The size limit

Incoming signals are capped at 1 MiB by default, and the cap is applied *while reading*. A
declared `Content-Length` above the limit is rejected before a byte is read; a chunked body is
cut off one byte past it. Nothing larger than the cap ever sits in memory. Map
`SignalsTooLargeException` to `413` in your framework's error handling. See [Security](/security/).
