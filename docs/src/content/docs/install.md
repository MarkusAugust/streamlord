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
    implementation("io.github.markusaugust.streamlord:streamlord-ktor:0.15.0")
    implementation("io.github.markusaugust.streamlord:streamlord-html:0.15.0")
    implementation("io.github.markusaugust.streamlord:streamlord-json-kotlinx:0.15.0")
}
```

```xml tab="Maven" group=install
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-ktor</artifactId>
  <version>0.15.0</version>
</dependency>
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-html</artifactId>
  <version>0.15.0</version>
</dependency>
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-json-kotlinx</artifactId>
  <version>0.15.0</version>
</dependency>
```

## Spring Boot 4

```kotlin tab="Gradle" group=install label="Build tool" sample=none
dependencies {
    implementation("io.github.markusaugust.streamlord:streamlord-spring:0.15.0")
    implementation("io.github.markusaugust.streamlord:streamlord-html:0.15.0")
    implementation("io.github.markusaugust.streamlord:streamlord-json-jackson:0.15.0")
}
```

```xml tab="Maven" group=install
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-spring</artifactId>
  <version>0.15.0</version>
</dependency>
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-html</artifactId>
  <version>0.15.0</version>
</dependency>
<dependency>
  <groupId>io.github.markusaugust.streamlord</groupId>
  <artifactId>streamlord-json-jackson</artifactId>
  <version>0.15.0</version>
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

<!-- deps=streamlord-json-kotlinx -->
- `org.jetbrains.kotlinx:kotlinx-serialization-json 1.11.0`
  - `org.jetbrains.kotlinx:kotlinx-serialization-core 1.11.0`

**`streamlord-json-jackson`**: Jackson 3

<!-- deps=streamlord-json-jackson -->
- `tools.jackson.core:jackson-databind 3.2.4`
  - `tools.jackson.core:jackson-core 3.2.4`
  - `com.fasterxml.jackson.core:jackson-annotations 2.22`

**`streamlord-json-jackson2`**: Jackson 2

<!-- deps=streamlord-json-jackson2 -->
- `com.fasterxml.jackson.core:jackson-databind 2.22.4`
  - `com.fasterxml.jackson.core:jackson-core 2.22.4`
  - `com.fasterxml.jackson.core:jackson-annotations 2.22`

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
so the Jackson you get is Boot's. Resolved under Boot 4.0.8, the codec's `jackson-databind 3.2.4`
becomes Boot's `3.1.5`.

**Gradle, with `platform(SpringBootPlugin.BOM_COORDINATES)`.** The highest version wins. A
platform contributes constraints rather than enforcement, so if Boot pins a Jackson older than the
one this release was built against, adding the codec **raises** your Jackson version: the same
build resolves `3.2.4`. If Boot's pin is newer, Boot's wins and nothing changes.

Both were resolved rather than reasoned about, and neither is a surprise you cannot undo:

```kotlin sample=none
dependencies {
    // Take Boot's Jackson, whatever it is.
    implementation("io.github.markusaugust.streamlord:streamlord-json-jackson:0.15.0") {
        exclude(group = "tools.jackson.core")
    }
}
```

Or pin it yourself, or use `enforcedPlatform` for Boot's BOM. All three work, because nothing on
our side is strict.

### When Boot's Jackson has a known vulnerability

Under Maven and `io.spring.dependency-management`, the Jackson your application runs is Boot's, so
that is the version a scanner such as Sonatype Lifecycle reports, whatever Streamlord asks for. On
27 October 2026 every released Boot pinned a `jackson-databind` with a published advisory:

| Spring Boot   | Jackson 3 | Jackson 2 |
| ------------- | --------- | --------- |
| 4.0.8, 4.1.1  | 3.1.5     | 2.21.5    |
| 4.0.9, 4.1.2  | 3.1.7     | 2.21.7    |
| 3.5.16        |           | 2.21.4    |

[CVE-2026-68497](https://github.com/advisories/GHSA-q4xh-88c3-wmh7), a denial of service when
reading a `Duration` or an `XMLGregorianCalendar`, is fixed in 3.1.6 and 2.21.6.
CVE-2026-110879, where the type parameters of a generic type id get past the
`PolymorphicTypeValidator` ([jackson-databind#6287](https://github.com/FasterXML/jackson-databind/pull/6287)),
is fixed in 3.1.8 and 2.21.8. Streamlord 0.14.0 asks for 3.2.4 and 2.22.4, which have both fixes,
but under Boot's management that request is ignored.

Raise Boot's own version property, on the minor line Boot is built against. The names differ
between Boot 4 and Boot 3, so take the block for yours.

**Boot 4.** `jackson-bom.version` is Jackson 3 and `jackson-2-bom.version` is Jackson 2; set the
second only if Jackson 2 is on your classpath too. In Maven:

```xml
<properties>
  <jackson-bom.version>3.1.8</jackson-bom.version>
  <jackson-2-bom.version>2.21.8</jackson-2-bom.version>
</properties>
```

In Gradle with `io.spring.dependency-management`:

```kotlin sample=none
extra["jackson-bom.version"] = "3.1.8"
extra["jackson-2-bom.version"] = "2.21.8"
```

**Boot 3.** There is only Jackson 2, and its property is `jackson-bom.version`. Boot 3 has no
`jackson-2-bom.version`, so setting it changes nothing, and the Boot 4 block above names a Jackson 2
BOM 3.1.8 that does not exist, which fails the build. In Maven:

```xml
<properties>
  <jackson-bom.version>2.21.8</jackson-bom.version>
</properties>
```

In Gradle with `io.spring.dependency-management`:

```kotlin sample=none
extra["jackson-bom.version"] = "2.21.8"
```

Not `implementation(platform("tools.jackson:jackson-bom:3.1.8"))`: under that plugin the line
resolves to Boot's 3.1.7 and says nothing. With `platform(SpringBootPlugin.BOM_COORDINATES)` you
need none of this on Boot 4, because the codec's 3.2.4 already wins. Each block was resolved and
run, the codec decoding through Boot's own mapper, on Boot 4.1.2 and 3.5.16. Drop the property once
Boot pins a fixed Jackson itself.

Outside Boot's management you run the Jackson Streamlord asks for. Streamlord 0.13.0 and older ask
for 3.2.3 and 2.22.3, which have CVE-2026-110879, so move to 0.14.0.

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
yourself, for example over your own sources in CI. See [The analysis](/analysis/).

## What you still have to bring

The Datastar client bundle. Streamlord is the server half; it does not ship, fetch or vendor the
JavaScript, and it has no opinion about whether you take it from a CDN or serve it from your own
static files. **1.0.4** is what this release speaks.

## Versions

JDK 21 builds Streamlord; the artifacts target JDK 17, the oldest realm both Ktor 3 and Spring
Framework 7 still tolerate. Kotlin 2.4 builds it, to the level of Kotlin 2.2, so a project on
Kotlin 2.2 or newer compiles against it; Boot 4.0 manages 2.2, and the documentation's examples are
compiled with 2.2 on every build. The Ktor adapter runs on Ktor 3.0 and newer. Its tests run on
the newest and on 3.0, all but the one for `HttpRequestLifecycle`, which came in 3.4. The Spring
adapter is compiled and tested against Framework 7.0 (Boot 4) on every build. Boot 3 is not
supported.

On Ktor 3.0 with the Netty engine, look at the Netty underneath. `ktor-server-netty` 3.0.0 brings
Netty 4.1.114.Final, which has known vulnerabilities, among them HTTP request smuggling in
`netty-codec-http` ([CVE-2026-33870](https://github.com/advisories/GHSA-pwqr-wmgm-9rr8)) and the
MadeYouReset attack on HTTP/2 ([CVE-2025-55163](https://github.com/advisories/GHSA-prj3-ccx8-p6x4)).
Streamlord brings neither Ktor nor Netty, so the remedy is in your build. Either move to a newer
Ktor (3.6.0 brings Netty 4.2.17.Final), or keep Ktor and raise Netty to the newest 4.1 release,
4.1.139.Final at the time of writing; neither had an advisory against it then. The adapter's
tests on Ktor 3.0 run on that Netty. In Gradle:

```kotlin sample=none
dependencies {
    implementation(platform("io.netty:netty-bom:4.1.139.Final"))
}
```

In Maven, import the same BOM:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.netty</groupId>
      <artifactId>netty-bom</artifactId>
      <version>4.1.139.Final</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

`./gradlew dependencyInsight --dependency netty-codec-http --configuration runtimeClasspath` or
`mvn dependency:tree` shows which Netty you run.

The servlet engines on Ktor 3.0 have the same problem. `ktor-server-tomcat-jakarta` 3.0.0 brings
Tomcat 10.1.30, which has known vulnerabilities, among them an authentication bypass in the DIGEST
authenticator ([CVE-2026-65905](https://github.com/advisories/GHSA-9xv2-5v5q-p794)). Raise it to
the newest 10.1 release, 10.1.61 at the time of writing. Tomcat has no BOM, so in Gradle:

```kotlin sample=none
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("org.apache.tomcat") && requested.version?.startsWith("10.1.") == true) {
            useVersion("10.1.61")
        }
    }
}
```

In Maven, list every `org.apache.tomcat` and `org.apache.tomcat.embed` artifact in
`mvn dependency:tree` at that version under `<dependencyManagement>`. Maven raises only what you
name, and `tomcat-catalina` alone brings eleven more, so a partial list mixes two Tomcat versions on
one classpath.

`ktor-server-jetty-jakarta` 3.0.0 brings Jetty 11.0.24. Raising it to 11.0.26 with
`platform("org.eclipse.jetty:jetty-bom:11.0.26")` helps, but Jetty 11 is no longer maintained in
the open, and 11.0.26 is the last release on Maven Central. It still has four advisories, among
them HTTP request smuggling ([CVE-2026-2332](https://github.com/advisories/GHSA-355h-qmc2-wpwf))
and an authority and `Host` mismatch
([CVE-2026-6790](https://github.com/advisories/GHSA-7p3p-8qv8-m2vh)). If you run the Jetty engine
in production, move to Ktor 3.5 or newer, whose Jetty 12 has all four fixed. The adapter's tests
on Ktor 3.0 run on Tomcat 10.1.61 and Jetty 11.0.26.

`streamlord-json-kotlinx` is the one module that can raise your Kotlin standard library. It takes
kotlinx.serialization 1.11.0, which asks for `kotlin-stdlib` 2.3.20, so in a Gradle project on
Kotlin 2.2 the newer one wins:

```text
$ ./gradlew dependencyInsight --dependency kotlin-stdlib --configuration runtimeClasspath
org.jetbrains.kotlin:kotlin-stdlib:2.3.20
   Selection reasons:
      - By conflict resolution: between versions 2.3.20, 2.2.21 and 2.1.0
```

That does no harm. The Kotlin 2.2 compiler reads a standard library one version ahead, and a
project on 2.2.0 with `allWarningsAsErrors` builds against it without a warning. The other way
round holds too: everything kotlinx.serialization 1.11.0 calls in the standard library is in
2.2.0 already, so it runs on the 2.2 standard library that Maven keeps when you declare it
yourself. Under Spring Boot 4.0 the BOM moves kotlinx.serialization down to 1.9.0, and the codec
runs on that too. To hold Gradle to your own standard library:

```kotlin sample=none
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib") {
        version { strictly("2.2.21") }
    }
}
```
