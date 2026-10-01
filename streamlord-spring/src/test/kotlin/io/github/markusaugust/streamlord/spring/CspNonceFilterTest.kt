package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.domain.CspNonce
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.server.MockServerWebExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CspNonceFilterTest {
    private fun filtered(filter: CspNonceFilter): Pair<MockHttpServletRequest, MockHttpServletResponse> {
        val request = MockHttpServletRequest("GET", "/")
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return request to response
    }

    @Test
    fun `the nonce on the request is the nonce in the header, and it is fresh per request`() {
        val filter = CspNonceFilter()
        val seen = mutableSetOf<String>()

        repeat(3) {
            val (request, response) = filtered(filter)
            val nonce = request.cspNonce

            assertEquals(
                "default-src 'self'; script-src 'self' 'nonce-$nonce'; object-src 'none'; base-uri 'self'",
                response.getHeader(CspNonce.HEADER),
            )
            seen += nonce
        }

        assertEquals(3, seen.size, "three requests, three nonces")
    }

    @Test
    fun `a policy of your own is written instead, from the same nonce`() {
        val (request, response) = filtered(CspNonceFilter(policy = { "script-src 'nonce-$it'" }))

        assertEquals("script-src 'nonce-${request.cspNonce}'", response.getHeader(CspNonce.HEADER))
    }

    @Test
    fun `report-only writes the other header and leaves the enforcing one alone`() {
        val (request, response) = filtered(CspNonceFilter(reportOnly = true))

        assertNull(response.getHeader(CspNonce.HEADER))
        assertTrue(response.getHeader(CspNonce.REPORT_ONLY_HEADER)!!.contains("'nonce-${request.cspNonce}'"))
    }

    @Test
    fun `a null policy still puts the nonce on the request and writes no header`() {
        val (request, response) = filtered(CspNonceFilter(policy = null))

        assertEquals(24, request.cspNonce.length)
        assertNull(response.getHeader(CspNonce.HEADER))
        assertNull(response.getHeader(CspNonce.REPORT_ONLY_HEADER))
    }

    @Test
    fun `a generator that returns something unusable fails the request, not the browser`() {
        assertFailsWith<IllegalArgumentException> { filtered(CspNonceFilter(nonce = { "" })) }
    }

    @Test
    fun `reading the nonce without the filter says what to register`() {
        val failure = assertFailsWith<IllegalStateException> { MockHttpServletRequest().cspNonce }

        assertTrue(failure.message!!.contains("CspNonceFilter"), failure.message!!)
    }

    @Test
    fun `the WebFlux half writes both ends onto the exchange`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))

        val nonce = exchange.installCspNonce()

        assertEquals(nonce, exchange.cspNonce)
        assertEquals(
            CspNonce.starterPolicy(nonce),
            exchange.response.headers.getFirst(CspNonce.HEADER),
        )
    }

    @Test
    fun `reading the nonce off an untouched exchange says what to call`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))

        val failure = assertFailsWith<IllegalStateException> { exchange.cspNonce }

        assertTrue(failure.message!!.contains("installCspNonce"), failure.message!!)
    }
}
