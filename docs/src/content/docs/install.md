---
title: "Install and modules"
description: "You need one artifact, two if you want the DSL, three at most. Here is which."
---

**Most projects take two.** One adapter for your framework, plus `streamlord-html` if you want to
write markup in Kotlin. A third only when you want your signals as data classes.

Start by copying the block for your framework, and read the rest of this page only if you want to
know why.

## Ktor

```kotlin tab="Gradle" group=install label="Build tool" sample=none
dependencies {
    implementation("io.github.markusaugust.streamlord:streamlord-ktor:0.11.1")
    implementation("io.github.markusaugust.streamlord:streamlord-html:0.11.1")
    implementation("io.github.markusaugust.streamlord:streamlord-json-kotlinx:0.11.1")
}
```

```xml tab="Maven" group=install
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-ktor</artifactId>
  <version>0.11.1</version>
</dependency>
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-html</artifactId>
  <version>0.11.1</version>
</dependency>
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-json-kotlinx</artifactId>
  <version>0.11.1</version>
</dependency>
```

## Spring Boot 4

```kotlin tab="Gradle" group=install label="Build tool" sample=none
dependencies {
    implementation("io.github.markusaugust.streamlord:streamlord-spring:0.11.1")
    implementation("io.github.markusaugust.streamlord:streamlord-html:0.11.1")
    implementation("io.github.markusaugust.streamlord:streamlord-json-jackson:0.11.1")
}
```

```xml tab="Maven" group=install
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-spring</artifactId>
  <version>0.11.1</version>
</dependency>
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-html</artifactId>
  <version>0.11.1</version>
</dependency>
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-json-jackson</artifactId>
  <version>0.11.1</version>
</dependency>
```

That is the whole answer. `streamlord-core` arrives with the adapter, so you never name it.

---

## Can I just take all of them?

No, and it is worth one paragraph to say why.

You would get `streamlord-json-kotlinx`, `streamlord-json-jackson` **and**
`streamlord-json-jackson2`: three JSON libraries doing one job. On Spring that is not merely
wasteful: Jackson 2 and Jackson 3 are different packages, and the wrong codec compiles happily
and then fails at runtime on an `ObjectMapper` that is not the one Spring configured.

You would also get both adapters, one of which has no framework to bind to.

There is no `streamlord-all` for the same reason. A single artifact would have to depend on Ktor
*and* Spring *and* three JSON libraries, and you use one of each at most.

## The three questions

**Which framework?** `streamlord-ktor` or `streamlord-spring`. Take exactly one. It is the only
one you must take, and it brings `streamlord-core` with it.

**How do you write HTML?** Take `streamlord-html` for the kotlinx.html DSL. Skip it if you write
markup as strings or render it from templates. Both work with the adapter alone, and both are
[first-class](/choosing-a-style/).

**Do you want signals as data classes?** Take one codec module. Skip it and the built-in reader
gives you `signals.string("query")` with no dependency at all.

If you answered *framework, no, no*, you take one artifact and you are done.

## Choosing the codec

Only if you answered yes to the third question. Match what your application already has, because
the point of these modules is to reuse the JSON library you have rather than add a second one.

| Codec module | Take it when |
|---|---|
| `streamlord-json-kotlinx` | you already use kotlinx.serialization |
| `streamlord-json-jackson` | Spring Boot 4, which ships Jackson 3 (`tools.jackson`) |
| `streamlord-json-jackson2` | your application already uses Jackson 2 (`com.fasterxml`) |

### What each one puts on your classpath

Resolved from the build, not from memory. `kotlin-stdlib` is left out of all three, and so is
`streamlord-core`, which arrives with your adapter either way.

**`streamlord-json-kotlinx`**

```deps=streamlord-json-kotlinx sample=none
streamlord-json-kotlinx
└── org.jetbrains.kotlinx:kotlinx-serialization-json 1.11.0
    └── org.jetbrains.kotlinx:kotlinx-serialization-core 1.11.0
```

**`streamlord-json-jackson`**: Jackson 3

```deps=streamlord-json-jackson sample=none
streamlord-json-jackson
└── tools.jackson.core:jackson-databind 3.2.3
    ├── tools.jackson.core:jackson-core 3.2.3
    └── com.fasterxml.jackson.core:jackson-annotations 2.22
```

**`streamlord-json-jackson2`**: Jackson 2

```deps=streamlord-json-jackson2 sample=none
streamlord-json-jackson2
└── com.fasterxml.jackson.core:jackson-databind 2.22.3
    ├── com.fasterxml.jackson.core:jackson-core 2.22.3
    └── com.fasterxml.jackson.core:jackson-annotations 2.22
```

Jackson 3 still takes its annotations from the 2.x artifact; that is Jackson's own arrangement,
not something Streamlord does.

### Do we pin your Jackson version?

No. Every dependency is published as a **soft** requirement: `requires` in the Gradle module
metadata, a plain `<version>` in the POM, never `strictly` and never a range. You can always
override it. But soft means different things to the two build tools, and it is worth knowing
which one you are using.

**Maven.** Your BOM wins outright. Spring Boot's `dependencyManagement` beats any version arriving
through a transitive dependency, so the Jackson you get is the Jackson Boot chose, and ours is
ignored.

**Gradle, with `io.spring.dependency-management`.** The same as Maven, and this is the setup
start.spring.io generates. That plugin pins every managed version, downwards as well as upwards,
so the Jackson you get is Boot's. Resolved under Boot 4.0.8, the codec's `jackson-databind 3.2.3`
becomes Boot's `3.1.5`.

**Gradle, with `platform(SpringBootPlugin.BOM_COORDINATES)`.** The highest version wins. A
platform contributes constraints rather than enforcement, so if Boot pins a Jackson older than the
one this release was built against, adding the codec **raises** your Jackson version: the same
build resolves `3.2.3`. If Boot's pin is newer, Boot's wins and nothing changes.

Both were resolved rather than reasoned about, and neither is a surprise you cannot undo:

```kotlin sample=none
dependencies {
    // Take Boot's Jackson, whatever it is.
    implementation("io.github.markusaugust.streamlord:streamlord-json-jackson:0.11.1") {
        exclude(group = "tools.jackson.core")
    }
}
```

Or pin it yourself, or use `enforcedPlatform` for Boot's BOM. All three work, because nothing on
our side is strict.

### And coroutines

Boot pins kotlinx-coroutines the same way: 1.10.2 under Boot 4.0 and 4.1, which is the version
Streamlord is compiled against. A Ktor application resolves a newer one through Ktor and that is
fine too; the Spring adapter's tests run on 1.10.2 and on the newest release.

### In practice you add few new jars

The trees above are what the codec pulls *if nothing else has pulled it already*. On Spring Boot,
Jackson is on your classpath before you add anything of ours, so the codec adds **one small jar**
and the rest resolves to a version already present, possibly bumped, as above. The same goes for
`streamlord-json-kotlinx` in a project that already serializes with kotlinx.

That is the whole reason there are three codec modules instead of one with a JSON library baked
in: the library is yours, and we would rather use it than ship a second copy of it.

**Taking no codec adds nothing at all.** The built-in reader is part of `streamlord-core` and has
its own RFC 8259 parser and writer, and `signals.string("query")` needs no library.

## All ten, for reference

The first law is that Streamlord brings nothing you do not already carry. This column is where
you check that.

| Module | Brings with it |
|---|---|
| `streamlord-core` | `kotlin-stdlib`, `kotlinx-coroutines-core` |
| `streamlord-html` | `kotlinx-html` |
| `streamlord-ktor` | nothing, Ktor is `compileOnly` |
| `streamlord-spring` | nothing, Spring and the servlet API are `compileOnly` |
| `streamlord-json-kotlinx` | `kotlinx-serialization-json` |
| `streamlord-json-jackson` | `jackson-databind` 3 |
| `streamlord-json-jackson2` | `jackson-databind` 2 |
| `streamlord-html-pro` | nothing beyond `streamlord-html` |
| `streamlord-analysis` | nothing beyond `streamlord-core` |
| `streamlord-test` | nothing beyond `streamlord-core`, and no test framework |

**The Soul** is `streamlord-core`: the protocol, the events, the encoder, a strict JSON engine and
the ports. It has no JSON library because it has its own RFC 8259 parser and writer.

**The Sword** is `streamlord-ktor` and **the Shield** is `streamlord-spring`. Neither is the port
the other was bolted onto; both are adapters over the same core, and Ktor and Spring WebMVC put
identical bytes on the wire. On WebFlux the frames are written by Spring's own encoder: the same
fields and the same data, in Spring's spelling.

**The Tongue** is `streamlord-html`: every `data-*` attribute, action and modifier of Datastar
1.0.4 as typed extension functions.

`streamlord-html-pro` is a separate, opt-in artifact for [Datastar Pro](/datastar-pro/). It
contains no Pro code and is inert without the bundle you licensed.

`streamlord-test` is assertions for your tests: it reads a Datastar response back into the
events it carried so a test can say what it means rather than compare the whole body as a string.
It binds no test framework, so it works under kotlin.test, JUnit, Kotest or TestNG alike. Take it
as a `testImplementation` and nowhere else. It is on [Testing](/testing/).

`streamlord-analysis` is the analysis behind the editor tooling: the Kotlin string reader, the
expression and markup rules, the signal and selector collectors. The editors are its first
consumers, and you never need it to write a stream. Take it when you want to run the same checks
yourself, for example over your own markup functions in a test. See [The editors](/editors/).

## What you still have to bring

The Datastar client bundle. Streamlord is the server half; it does not ship, fetch or vendor the
JavaScript, and it has no opinion about whether you take it from a CDN or serve it from your own
static files. **1.0.4** is what this release speaks.

## Versions

JDK 21 builds Streamlord; the artifacts target JDK 17, the oldest realm both Ktor 3 and Spring
Framework 7 still tolerate. Kotlin 2.4. The Spring adapter is compiled and tested against
Framework 7.0 (Boot 4) on every build. Boot 3 is not supported.
