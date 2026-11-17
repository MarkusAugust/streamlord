package io.github.markusaugust.streamlord.spring.test

import jakarta.servlet.http.Cookie
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
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
 * with an empty body, and WebTestClient runs one. These run them all.
 */

/**
 * A [ClientHttpRequestFactory] over this MockMvc that answers once every async cycle has run, so a
 * `datastarStream` comes back whole:
 *
 * ```kotlin
 * val client = RestTestClient.bindToServer(mockMvc.datastarRequestFactory()).build()
 * ```
 *
 * A request that leaves an exception unresolved answers 500 with it as the body, as Spring's own
 * MockMvc binding does. A stream that has not ended after [asyncTimeout] throws: one that stays
 * open needs a real server, and `LiveDatastarStream` from streamlord-test.
 *
 * The JVM names of both helpers stay plain through `@JvmName`, as `datastarStream`'s do.
 */
@JvmName("datastarRequestFactory")
public fun MockMvc.datastarRequestFactory(asyncTimeout: Duration = 10.seconds): ClientHttpRequestFactory =
    ClientHttpRequestFactory { uri, method -> DispatchingRequest(this, asyncTimeout, method, uri) }

/**
 * Sends a request the way Datastar's client does, with the `Datastar-Request` header and [signals]
 * where it puts them, the query for GET and DELETE and a JSON body otherwise, and returns once
 * every async cycle has run. The status, the headers and the events are all in the result:
 *
 * ```kotlin
 * val events = datastarEvents(mvc.datastar(HttpMethod.GET, "/feed", """{"heads":13}""").response.contentAsString)
 * ```
 *
 * A stream that has not ended after [asyncTimeout] throws, as with [datastarRequestFactory].
 */
@JvmName("datastar")
public fun MockMvcTester.datastar(
    method: HttpMethod,
    uri: String,
    signals: String? = null,
    asyncTimeout: Duration = 10.seconds,
): MvcTestResult {
    var result =
        method(method)
            .uri(uri)
            .header("Datastar-Request", "true")
            .header(HttpHeaders.ACCEPT, "text/event-stream, text/html, application/json")
            .apply {
                when {
                    signals == null -> Unit
                    method == HttpMethod.GET || method == HttpMethod.DELETE -> param("datastar", signals)
                    else -> contentType(MediaType.APPLICATION_JSON).content(signals)
                }
            }.asyncExchange()
    while (result.unresolvedException == null && result.request.isAsyncStarted) {
        awaitCycle(result.mvcResult, asyncTimeout)
        result = perform(MockMvcRequestBuilders.asyncDispatch(result.mvcResult))
    }
    return result
}

private fun awaitCycle(
    started: MvcResult,
    timeout: Duration,
) {
    try {
        started.getAsyncResult(timeout.inWholeMilliseconds)
    } catch (e: IllegalStateException) {
        throw StreamDidNotEnd(timeout, e)
    }
}

private class StreamDidNotEnd(
    timeout: Duration,
    cause: Throwable,
) : IllegalStateException(
        "The answer had not ended after $timeout. A stream that stays open needs a real server; " +
            "read it with LiveDatastarStream from streamlord-test.",
        cause,
    )

private class DispatchingRequest(
    private val mvc: MockMvc,
    private val asyncTimeout: Duration,
    method: HttpMethod,
    uri: URI,
) : MockClientHttpRequest(method, uri) {
    override fun executeInternal(): ClientHttpResponse {
        val request = MockMvcRequestBuilders.request(method, uri).headers(headers).content(bodyAsBytes)
        for (cookie in headers[HttpHeaders.COOKIE].orEmpty().flatMap { it.split(';') }) {
            val (name, value) = cookie.trim().split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (name.isNotEmpty()) request.cookie(Cookie(name, value))
        }
        val result =
            try {
                var result = mvc.perform(request).andReturn()
                while (result.request.isAsyncStarted) {
                    awaitCycle(result, asyncTimeout)
                    result = mvc.perform(MockMvcRequestBuilders.asyncDispatch(result)).andReturn()
                }
                result
            } catch (e: StreamDidNotEnd) {
                throw e
            } catch (e: Exception) {
                return MockClientHttpResponse(e.toString().toByteArray(), HttpStatus.INTERNAL_SERVER_ERROR)
            }
        val servlet = result.response
        val body = servlet.contentAsByteArray.takeIf { it.isNotEmpty() } ?: servlet.errorMessage.orEmpty().toByteArray()
        return MockClientHttpResponse(body, HttpStatusCode.valueOf(servlet.status)).also { response ->
            for (name in servlet.headerNames) servlet.getHeaders(name).forEach { response.headers.add(name, it) }
        }
    }
}
