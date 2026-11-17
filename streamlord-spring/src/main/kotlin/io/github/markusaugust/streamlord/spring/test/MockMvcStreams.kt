package io.github.markusaugust.streamlord.spring.test

import io.github.markusaugust.streamlord.core.client.ClientRequest
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import jakarta.servlet.AsyncEvent
import jakarta.servlet.http.Cookie
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.mock.web.MockAsyncContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * A stream runs in an async cycle of its own, and a `suspend` controller adds one before it. MockMvc
 * leaves each cycle to the test: RestTestClient bound to MockMvc runs none of them and answers 200
 * with what the stream had written by then, most often nothing, and WebTestClient runs one. These
 * run them all.
 */

/**
 * A [ClientHttpRequestFactory] over this MockMvc that answers once every async cycle has run, so a
 * `datastarStream` comes back whole:
 *
 * ```kotlin
 * val client = RestTestClient.bindToServer(mockMvc.datastarRequestFactory()).build()
 * ```
 *
 * It sends what the test writes and nothing more. A request that leaves an exception unresolved,
 * a failure after a stream's first event among them, answers 500 with it as the body, as Spring's
 * own MockMvc binding does. An answer that has not ended after [asyncTimeout], all its cycles
 * together, is stopped and throws: a stream that stays open needs a real server, and
 * `LiveDatastarStream` from streamlord-test.
 *
 * The JVM names of both helpers stay plain through `@JvmName`, as `datastarStream`'s do.
 */
@JvmName("datastarRequestFactory")
public fun MockMvc.datastarRequestFactory(asyncTimeout: Duration = 10.seconds): ClientHttpRequestFactory =
    ClientHttpRequestFactory { uri, method ->
        Deadline.check(asyncTimeout)
        DispatchingRequest(this, asyncTimeout, method, uri)
    }

/**
 * Sends a request the way Datastar's client does, with the `Datastar-Request` header and [signals]
 * where it puts them, the query for GET and DELETE and a JSON body otherwise, and returns once
 * every async cycle has run. The status, the headers and the events are all in the result:
 *
 * ```kotlin
 * val events = datastarEvents(mvc.datastar(HttpMethod.GET, "/feed", """{"heads":13}""").response.contentAsString)
 * ```
 *
 * An answer that has not ended after [asyncTimeout] is stopped and throws, as with
 * [datastarRequestFactory].
 */
@JvmName("datastar")
public fun MockMvcTester.datastar(
    method: HttpMethod,
    uri: String,
    signals: String? = null,
    asyncTimeout: Duration = 10.seconds,
): MvcTestResult {
    val deadline = Deadline(asyncTimeout)
    val name = method.name()
    var result =
        method(method)
            .uri(uri)
            .apply {
                for ((header, value) in ClientRequest.headers(name)) header(header, value)
                if (signals != null) {
                    if (ClientRequest.bodyless(name)) param(DatastarProtocol.SIGNALS_PARAMETER, signals) else content(signals)
                }
            }.asyncExchange()
    while (result.unresolvedException == null && result.request.isAsyncStarted) {
        deadline.await(result.mvcResult)
        result = perform(MockMvcRequestBuilders.asyncDispatch(result.mvcResult))
    }
    return result
}

/** The time an answer has, all its cycles together, from when it was sent. */
private class Deadline(
    private val timeout: Duration,
) {
    private val end = System.nanoTime() + check(timeout).inWholeNanoseconds

    /** Waits for [started]'s cycle to end; past the deadline, stops the stream and throws. */
    fun await(started: MvcResult) {
        val left = end - System.nanoTime()
        try {
            // In whole milliseconds rounded up, so a return before the deadline is an interrupt.
            if (left > 0) {
                started.getAsyncResult((left + 999_999) / 1_000_000)
                return
            }
        } catch (_: IllegalStateException) {
        }
        // Spring swallows the interrupt while it waits and clears the flag.
        if (System.nanoTime() < end) {
            Thread.currentThread().interrupt()
            throw InterruptedException("Interrupted while waiting for the answer")
        }
        // What a server does on its async timeout: Spring hears of it and interrupts the stream's
        // thread, and the request completes, after which a write to the response throws. The
        // interrupt alone can be lost in a write under way; the failed write is not.
        val context = started.request.asyncContext as MockAsyncContext
        for (listener in context.listeners) listener.onTimeout(AsyncEvent(context))
        context.complete()
        throw StreamDidNotEnd(timeout)
    }

    companion object {
        fun check(timeout: Duration): Duration = timeout.also { require(it.isPositive()) { "asyncTimeout must be positive, was $it" } }
    }
}

private class StreamDidNotEnd(
    timeout: Duration,
) : IllegalStateException(
        "The answer had not ended after $timeout. A stream that stays open needs a real server; " +
            "read it with LiveDatastarStream from streamlord-test.",
    )

private class DispatchingRequest(
    private val mvc: MockMvc,
    private val asyncTimeout: Duration,
    method: HttpMethod,
    uri: URI,
) : MockClientHttpRequest(method, uri) {
    override fun executeInternal(): ClientHttpResponse {
        val deadline = Deadline(asyncTimeout)
        val result =
            try {
                val request = MockMvcRequestBuilders.request(method, uri).headers(headers).content(bodyAsBytes)
                // One Cookie header, `a=1; b=2`, as RestClient writes it; MockMvc does not read it.
                for (cookie in headers[HttpHeaders.COOKIE].orEmpty().flatMap { it.split(';') }) {
                    val (name, value) = cookie.trim().split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    if (name.isNotEmpty()) request.cookie(Cookie(name, value))
                }
                var result = mvc.perform(request).andReturn()
                while (result.request.isAsyncStarted) {
                    deadline.await(result)
                    result = mvc.perform(MockMvcRequestBuilders.asyncDispatch(result)).andReturn()
                }
                result
            } catch (e: Exception) {
                if (e is StreamDidNotEnd || e is InterruptedException) throw e
                return MockClientHttpResponse(e.toString().toByteArray(), HttpStatus.INTERNAL_SERVER_ERROR)
            }
        val servlet = result.response
        val body = servlet.contentAsByteArray.takeIf { it.isNotEmpty() } ?: servlet.errorMessage.orEmpty().toByteArray()
        return MockClientHttpResponse(body, HttpStatusCode.valueOf(servlet.status)).also { response ->
            for (name in servlet.headerNames) servlet.getHeaders(name).forEach { response.headers.add(name, it) }
        }
    }
}
