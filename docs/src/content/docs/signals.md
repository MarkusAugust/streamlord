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
down yourself. A body must be declared `Content-Type: application/json`, as Datastar's client
declares every body it sends signals in; one declared otherwise, or not at all, is refused with
`SignalsMediaTypeException`, a 415, for the reason on [Security](/security/#forms-from-other-sites).

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

## Declaring them from the class

The page that sends those signals can declare them from the same class, so the names cannot drift
apart. `toJsonValue` encodes a value with the codec and hands the DSL a JSON object, whose keys
become the page's signals as written:

```kotlin sample=ktor-routing
get("/search") {
    call.respondElements(elements {
        div { dataSignals(call.streamlord.codec.toJsonValue(SearchSignals())) }
    })
}
```

That writes `data-signals="{"query":"","page":1}"`, the names `readSignalsOr(SearchSignals())`
reads. The codec chooses them, so a `@SerialName` or a Jackson `@JsonProperty` reaches the page
too. Pass the value as a pair, `"search" to codec.toJsonValue(...)`, and the fields land under
`$search` instead, where a read into `SearchSignals` will not find them.

What the codec leaves out, the page does not declare. A field that is `null` declares nothing,
because the client holds no null: it removes a signal patched to `null`, and an expression that
reads it, or a `data-bind` on it, gets `""`. A `null` in a list is held as `""` too. So a form
with `age: Int? = null` can come back from the browser as `"age": ""`, which a read into `Int?`
refuses. Give such a field the value the page starts it with, `""` or `0`, or make the type take
what the browser sends. The editors warn on a pair written `"selected" to null` in `dataSignals`.
A codec set to skip defaults, kotlinx's `encodeDefaults = false` or Jackson's
`NON_DEFAULT`, writes no field at its default, so `SearchSignals()` declares none.

A key keeps the codec's spelling, hyphens included. `@SerialName("first-name")` declares the
signal `first-name`, which an expression reaches as `$['first-name']`, written
`signal("['first-name']")`; `signal("first-name")` throws, since a signal name holds no hyphen
(see [Casing](/casing/)). Keep the names in camelCase and the question never comes up.

The built-in codec cannot encode a data class either, so `toJsonValue` needs the codec from step
one. In Spring it is on the `Streamlord` bean:

```kotlin sample=spring-controller
@RestController
class SearchPageController(private val streamlord: Streamlord) {
    @GetMapping("/search", produces = ["text/html;charset=UTF-8"])
    fun page(): String =
        elements {
            div { dataSignals(streamlord.codec.toJsonValue(SearchSignals())) }
        }
}
```

`toJsonValue` lives with the codec, in `io.github.markusaugust.streamlord.core.port.driven`.

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
on [The analysis](/analysis/#signals-the-pages-never-declare).

If you also want it to fail at request time, that is your codec's own setting rather than
something Streamlord adds: `KotlinxSignalsCodec(json = Json { ignoreUnknownKeys = false })`
refuses a body carrying anything the type does not name. It is worth a test and a poor fit for
production, because it makes every handler fail the moment any page gains a signal. The Jackson
codecs have no such setting: they ignore unknown keys even when your mapper is set to refuse
them, since that is Jackson 2's own default and every handler would otherwise fail.

## Which codec

| Module | Codec | Use it when |
|---|---|---|
| `streamlord-json-kotlinx` | `KotlinxSignalsCodec` | your application already uses kotlinx.serialization |
| `streamlord-json-jackson` | `JacksonSignalsCodec` | Spring Boot 4, which ships Jackson 3 (`tools.jackson`) |
| `streamlord-json-jackson2` | `Jackson2SignalsCodec` | your application already uses Jackson 2 (`com.fasterxml`) |
| none | the built-in reader | you are happy reading by name |

The two Jackson modules exist because Jackson 3 moved package. Taking the wrong one compiles and
then fails at runtime on an `ObjectMapper` that is not the one Spring configured.

## Where the codecs differ

All three ignore keys the type does not name and refuse a body with anything after the JSON
value. All three also refuse a `null` for a primitive property such as `val id: Long`, and a
missing Kotlin parameter that has no default and cannot be `null`. For Jackson, Streamlord
enforces these whatever your mapper is set to, because Jackson 2 and Spring Boot's mapper would
otherwise turn a missing `val id: Long` into `0` and refuse every request that carries a signal
the type does not name. The `null` is refused even for a primitive with a default, and even when
the mapper has the Kotlin module's `NullIsSameAsDefault` on: declare the property `Int?` if a
page may send `null`. A Java class read through Jackson, such as a record, may leave out a
component that takes `null`, which then arrives as `null`; the table below has what the two
Jackson codecs make of a missing primitive.

A `null` inside a `List<Int>`, `Set<Item>`, `Array<String>` or `Map<String, Int>` is refused too,
however deep the lists and maps nest, as in `Map<String, List<Int>>`; declare the element `Int?`,
as in `List<Int?>`, if a page may send one. kotlinx refuses it wherever the type sits. Jackson
refuses it in two places:

- a parameter of a Kotlin class's constructor, or of its `@JsonCreator` factory, that names the
  element type, as `data class Signals(val ids: List<Int>)` does;
- the type you read whole, when it is itself a list, set, array or map, as in
  `readSignals<Map<String, List<Int>>>()`.

Everywhere else Jackson hands your handler the `null`:

- a `var` declared in the class body rather than in the constructor;
- a value class holding a list, such as `@JvmInline value class Ids(val v: List<Int>)`;
- a property typed by a type parameter, as `v: T` or `items: List<T>`, including the parts of a
  class such as `Pair<List<Int>, Int>` read whole; the table below has how the two Jackson codecs
  differ on `List<T>`;
- a Java class, such as a record, since a Java `List<Integer>` may hold `null`.

With Jackson 3 the check on a constructor parameter is the Kotlin module's `StrictNullChecks`,
which is on unless your mapper turns it off; turned off, only the type read whole is checked. With
Jackson 2 Streamlord adds the same check whatever the mapper says. Neither follows a mapper whose
default is to skip a `null` in a collection, as
`setDefaultSetterInfo(JsonSetter.Value.forContentNulls(Nulls.SKIP))` sets it: the parameter still
refuses one. To have it dropped, put `@JsonSetter(contentNulls = Nulls.SKIP)` on the parameter.

Objects and arrays nested more than 64 deep are refused before any codec reads them, so all
three, and the built-in reader, answer a body of twenty thousand `[` with a 400 rather than a
`StackOverflowError`.

A Kotlin class or interface read through Jackson needs `jackson-module-kotlin` on the mapper.
Without it Jackson drops what the browser sent or leaves `null` in a property that cannot hold
one, so the codec throws `SignalsCodecException`, a 500, instead; it does so even for a class
Jackson could build without the module, since that class loses its null-safety all the same.

They differ on the rest:

| Body or value | kotlinx | Jackson 3 and 2 |
|---|---|---|
| `{"page":3.7}` into `page: Int` | refused | `3` |
| `{"page":1.0}` into `page: Int`, or `{"id":12.0}` into `id: Long` | refused | `1`, `12` |
| `{"page":" 3"}` into `page: Int` | refused | `3` |
| `{"page":"3"}` into `page: Int` | `3` | `3` |
| `{"name":5}` into `name: String` | refused | `"5"` |
| `{"name":true}` into `name: String` | refused | `"true"` |
| `{"b":1}` into `b: Boolean` | refused | `true` |
| `{"d":"NaN"}` into `d: Double` | refused | `NaN` |
| `{"d":1e400}` into `d: Double` | refused | `Infinity` |
| `{"d":-0}` into `d: Double` | `-0.0` | `0.0` |
| `{"c":0}` or `{"c":"0"}` into `c: Colour`, an enum | refused | the first constant, `RED` |
| a raw tab or other control character inside a string, which JSON does not allow | read as it is | refused |
| `{}` into `q: String?` with no default | refused | `null` |
| `{"v":null}` into `v: T` of `data class One<T>(val v: T)`, read as `One<Int>` | refused | `null` |
| `{"items":[1,null]}` into `items: List<T>`, read as `Page<Int>` | refused | Jackson 3 refuses it; Jackson 2 reads `[1, null]` |
| `{"items":[1,null]}` into `items: List<T>`, read as `Page<Int?>` | `[1, null]` | Jackson 3 refuses it, unless `StrictNullChecks` is off; Jackson 2 reads `[1, null]` |
| `{"name":"a"}` into a Java record with an `int n` | `SignalsCodecException`, a 500: no serializer | Jackson 3 reads `n` as `0`; Jackson 2 refuses it |
| reading into `Map<String, Any?>` | `SignalsCodecException`, a 500 | read |
| writing `Double.NaN` or an infinity | `SignalsCodecException` | the string `"NaN"`, so the signal changes type in the browser |
| writing a `Map<String, Any?>` | `SignalsCodecException` | written |

With Jackson, a client can put `NaN` or `Infinity` into a `Double` your handler computes with;
check the value with `isFinite()` where it matters, or read it through kotlinx.

A body that is refused throws `SignalsDecodeException`, which `StreamlordExceptionHandler`
answers with a 400.

A string holding half of a surrogate pair, which `JSON.stringify` sends as `\ud800`, comes back
from every codec with the half as it is, in the codec's own text. UTF-8 has no bytes for it, so
Streamlord writes it as `\ud800` when the patch goes out, and the browser gets back what it sent,
as from the built-in writer. In elements there is no way to carry one, and it is sent as
U+FFFD, the replacement character the browser would put there itself.

## Numbers past 2^53

The browser holds every number as a double, which is exact up to 2^53, 9007199254740992. A
`Long` beyond that, such as a snowflake id, comes back as another number: a page given
`{"id":9007199254740993}` sends `9007199254740992`. The codecs and the DSL write the digits as
they are and cannot warn you, so keep such ids in a `String`.

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

A signal patched to `null` is **removed**, because the protocol makes a signal patch an RFC 7386
merge patch, and Datastar's client follows the RFC for one like this. That is not a quirk of
Streamlord; it is how you delete. Where the client parts from the RFC, on arrays, on
`onlyIfMissing` and on a patch that is not an object, `mergePatchAsClient` in `streamlord-core`
folds a patch the way the client does.

A patch the client fails on partway, such as a `length` an array cannot take, makes
`mergePatchAsClient` throw `SignalPatchException` with the store as the client keeps it, the keys
before the failing one applied, and both Stream Inspectors show that store and the reason. In
the browser the damage outlasts the patch: Datastar 1.0.4 leaves its update batch open after the
throw, so the bindings already on the page stop redrawing until it is reloaded. Measured in
Chrome, a `data-text` stayed where it was while clicks moved its signal on, while element
patches still landed and a binding added after the throw showed the current value.

Two kinds of patch are refused on purpose, whatever the client does with them, with `refused`
set on the exception, and the inspectors say so and what the client does. One is a key
`__proto__` anywhere in the patch, whatever it holds. Where its value is an object with keys,
such as `{"__proto__":{"x":1}}`, the client mostly throws at that key, and the bindings stop
redrawing as above; otherwise it keeps an object that is no longer plain JSON. The other is more
indices past the end of an array than `holeLimit`, which the client takes and keeps as holes.

A dotted name reaches a nested signal: `patchSignals("form.email" to "")` patches `$form.email`,
the signal `data-signals:form.email` declares, and `removeSignals("form.email")` removes it.
Where `form` is missing or holds a plain value, the client makes it an empty object first, as a
merge patch says.

Some keys Datastar's client cannot hold, and a patch carrying one throws
`DatastarEventValidationException` before anything is sent, whether it came from pairs, a codec
or a JSON string, at any depth:

- `__proto__`, which the client never stores but sets as the prototype of the object holding it.
  With `{"x": 1}`, `{"0": 1}` or `{"length": 5}` under it, its merge throws halfway through, and
  the bindings already on the page stop redrawing until it is reloaded. With `{}` or
  `{"x": null}` it throws nothing, and `{"__proto__": {"y": 1}}` under it sets `y` on
  `String.prototype`;
- a property every object already has (`constructor`, `toString`, `valueOf`, `hasOwnProperty`
  and the rest of `Object.prototype`) with an object under it, which the client writes into the
  built-in property before every request: `constructor.prototype` reaches every object on the
  page, and `toString.name` makes every later request fail. A plain value or an array under
  such a key arrives as sent, so `{"team": {"constructor": "Ferrari"}}` goes through; what the
  page then writes under it is its own doing;
- a key holding `.`, which the client stores as one key that `$a.b` does not reach, and, outside
  a list, drops when it sends the signals with a request. It is refused inside a list too.

A name in the pairs is held to more, since an object may come under any of its parts: no part
may be empty or one of those properties, so `patchSignals("constructor" to 1)` throws too. The
same holds for every name the HTML DSL writes.

## Signals that stay in the browser

Not every signal is for the server. Whether a drawer is showing, which row the cursor is on, a
draft the reader has not sent yet: these are state the page needs and no handler reads.

Start the name with an underscore and it stays behind. Every backend action (`@get`, `@post` and
the rest) sends the store through a filter whose default excludes `/(^|\.)_/`, so `_drawerOpen`
never travels, and neither does `form._draft`:

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
    dataSignals("cart" to mapOf("items" to listOf(1, 2)), "selected" to listOf(3, 5))
    button {
        dataOnClick(post("/cart") { filterSignals = SignalFilter.include("^cart\\.") })
        +"Save cart"
    }
    button {
        dataOnClick(post("/rows/delete") { payloadExpr = $$"{ids: $selected}" })
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
cut off one byte past it. Nothing larger than the cap ever sits in memory. Signals sent by `@get`
or `@delete` travel in the URL, which the server refuses at 4 to 8 KiB, long before the cap,
so send large ones with `@post` ([Security](/security/#bounded-input) has the numbers). Map
`SignalsTooLargeException` to `413` in your framework's error handling, and a body that will not
parse or decode to `400`. See [Security](/security/).
