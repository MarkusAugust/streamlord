---
title: "Java"
description: "Streamlord is a Kotlin library. If your service is written in Java, use the official Datastar Java SDK."
---

Streamlord is a Kotlin library, and it is Kotlin all the way down: suspend functions, flows,
extension functions and default arguments. It offers no Java-facing API.

If your service is written in Java, use the official SDK instead.

## The official Datastar Java SDK

[starfederation/datastar-java](https://github.com/starfederation/datastar-java). MIT, Java 17 or
later, with adapters for servlets and JAX-RS.

```kotlin sample=none
dependencies {
    implementation("dev.data-star:datastar-java-sdk-core:1.0.0")
    implementation("dev.data-star:datastar-java-sdk-jaxrs:1.0.0")   // JAX-RS only
}
```

```xml
<dependency>
    <groupId>dev.data-star</groupId>
    <artifactId>datastar-java-sdk-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

## The editors still serve you

The [VS Code extension and the IntelliJ plugin](/editors/) read Datastar as a language in your
HTML and template files: diagnostics with quick fixes, completion, hover and highlighting. They
do not care which library sends the bytes, so install them whichever SDK you end up with.
