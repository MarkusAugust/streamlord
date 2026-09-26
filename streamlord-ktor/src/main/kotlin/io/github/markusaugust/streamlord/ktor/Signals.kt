package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.SignalsTooLargeException
import io.github.markusaugust.streamlord.core.application.Signals
import io.github.markusaugust.streamlord.core.port.driven.IncomingRequest
import io.github.markusaugust.streamlord.core.port.driven.isDatastarRequest
import io.github.markusaugust.streamlord.core.port.driven.rejectDeclaredLengthAbove
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlin.reflect.typeOf

/**
 * The [IncomingRequest] port implemented over a Ktor call.
 *
 * The body is read straight from the receive channel with a ceiling: a declared
 * `Content-Length` above the limit is rejected before a byte is read, and a chunked body is cut
 * off one byte past the limit. Nothing larger than the limit ever sits in memory.
 */
internal class KtorIncomingRequest(private val call: ApplicationCall) : IncomingRequest {
    override val method: String get() = call.request.httpMethod.value
    override fun queryParameter(name: String): String? = call.request.queryParameters[name]
    override fun header(name: String): String? = call.request.header(name)

    override suspend fun bodyText(maxBytes: Int): String {
        rejectDeclaredLengthAbove(maxBytes, call.request.contentLength())
        val bytes = call.receiveChannel().readBuffer(maxBytes.toLong() + 1).readByteArray()
        if (bytes.size > maxBytes) throw SignalsTooLargeException(bytes.size.toLong(), maxBytes)
        return bytes.decodeToString()
    }
}

/** This call as the core's [IncomingRequest] port. */
public fun ApplicationCall.asIncomingRequest(): IncomingRequest = KtorIncomingRequest(this)

/** `true` when the request came from a Datastar fetch action. */
public val ApplicationCall.isDatastarRequest: Boolean get() = asIncomingRequest().isDatastarRequest

/** The raw JSON text of the signals, or `null` when the request carried none. */
public suspend fun ApplicationCall.readSignalsJson(): String? = streamlord.readSignalsJson(asIncomingRequest())

/**
 * The signals as a dependency-free [Signals] object; empty when the request carried none.
 *
 * ```kotlin
 * val query = call.readSignals().string("search") ?: ""
 * ```
 */
public suspend fun ApplicationCall.readSignals(): Signals = streamlord.readSignals(asIncomingRequest())

/**
 * The signals decoded into [T] by the configured codec, or `null` when the request carried none.
 *
 * ```kotlin
 * val signals = call.readSignals<SearchSignals>() ?: SearchSignals()
 * ```
 */
@JvmName("readSignalsTyped")
public suspend inline fun <reified T : Any> ApplicationCall.readSignals(): T? =
    streamlord.readSignals(asIncomingRequest(), typeOf<T>())

/** The signals decoded into [T], or [default] when the request carried none. */
public suspend inline fun <reified T : Any> ApplicationCall.readSignalsOr(default: T): T = readSignals<T>() ?: default
