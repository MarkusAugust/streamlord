package io.github.markusaugust.streamlord.spring.test

import io.github.markusaugust.streamlord.core.client.ClientRequest
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
 * `LiveDatastarStream` from streamlord-test. An interrupt of the test's thread stops the stream too,
 * and comes back as an [InterruptedException] with the thread still interrupted.
 *
 * Both helpers are for Kotlin: the `Duration` parameter gives them JVM names Java cannot call.
 */
public fun MockMvc.datastarRequestFactory(asyncTimeout: Duration = 10.seconds): ClientHttpRequestFactory {
    checkTimeout(asyncTimeout)
    return ClientHttpRequestFactory { uri, method -> DispatchingRequest(this, asyncTimeout, method, uri) }
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
 * [datastarRequestFactory], and so does an interrupt.
 */
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
        if (!deadline.await(result.mvcResult)) break
        result = perform(MockMvcRequestBuilders.asyncDispatch(result.mvcResult))
    }
    return result
}

private fun checkTimeout(timeout: Duration) = require(timeout.isPositive()) { "asyncTimeout must be positive, was $timeout" }

/** The time an answer has, all its cycles together, from when it was sent. */
private class Deadline(
    private val timeout: Duration,
) {
    private val end = TimeSource.Monotonic.markNow() + timeout.also(::checkTimeout)

    /**
     * Waits for [started]'s cycle to end, and says whether it ended in a dispatch, which the caller
     * then performs, or in a `complete()`, after which the response stands as it is. Past the
     * deadline, or on an interrupt, it stops the stream and throws. Not through `getAsyncResult`,
     * which swallows an interrupt and clears the flag, and waits out its own timeout for a cycle
     * that completes without a dispatch.
     */
    fun await(started: MvcResult): Boolean {
        val request = started.request
        val context = request.asyncContext as MockAsyncContext
        val ended = CountDownLatch(1)
        val dispatched = AtomicBoolean()
        // A dispatch handler runs at once when the cycle has dispatched already.
        context.addDispatchHandler {
            dispatched.set(true)
            ended.countDown()
        }
        context.addListener(Completion(ended))
        if (!request.isAsyncStarted) ended.countDown()
        val left = -end.elapsedNow()
        val done =
            try {
                // await throws on an interrupt even when the count is already down.
                when {
                    left.isInfinite() -> true.also { ended.await() }
                    left.isPositive() -> ended.await(left.inWholeNanoseconds, TimeUnit.NANOSECONDS)
                    else -> false
                }
            } catch (e: InterruptedException) {
                stop(context)
                Thread.currentThread().interrupt()
                throw e
            }
        if (!done) {
            stop(context)
            throw StreamDidNotEnd(timeout)
        }
        return dispatched.get()
    }

    /*
     * What a server does on its async timeout: Spring hears of it and interrupts the stream's
     * thread, and the request completes, after which a write to the response throws. The interrupt
     * alone can be lost in a write under way; the failed write is not.
     */
    private fun stop(context: MockAsyncContext) {
        for (listener in context.listeners) listener.onTimeout(AsyncEvent(context))
        context.complete()
    }

    private class Completion(
        private val ended: CountDownLatch,
    ) : AsyncListener {
        override fun onComplete(event: AsyncEvent) = ended.countDown()

        override fun onTimeout(event: AsyncEvent) = Unit

        override fun onError(event: AsyncEvent) = Unit

        override fun onStartAsync(event: AsyncEvent) = Unit
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
                    if (!deadline.await(result)) break
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
