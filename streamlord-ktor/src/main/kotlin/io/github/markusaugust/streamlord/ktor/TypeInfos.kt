package io.github.markusaugust.streamlord.ktor

import io.ktor.util.reflect.TypeInfo
import kotlin.reflect.typeOf

/*
 * Ktor's reified helpers, AttributeKey<T>(name), call.respond(message) and call.receive<T>() among
 * them, inline whichever TypeInfo constructor the Ktor they are compiled against has, and the one
 * 3.6.0 added is not in any earlier Ktor 3. Ktor is the consumer's, so the adapter builds its
 * TypeInfo from the constructor every Ktor 3 has, and testOldestKtor holds it to that.
 */
internal inline fun <reified T : Any> portableTypeInfo(): TypeInfo = TypeInfo(T::class, typeOf<T>())
