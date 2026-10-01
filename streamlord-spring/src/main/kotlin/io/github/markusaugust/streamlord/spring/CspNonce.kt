package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.domain.CspNonce
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.server.ServerWebExchange

/**
 * Generates one CSP nonce per request, writes the policy header from it, and hands the same
 * value to your markup through [cspNonce]. Both ends come from one variable, so they cannot
 * drift.
 *
 * Register it as a bean and Spring Boot puts it in the chain:
 *
 * ```kotlin
 * @Bean
 * fun cspNonceFilter(): CspNonceFilter = CspNonceFilter()
 * ```
 *
 * Then read it where the page is built:
 *
 * ```kotlin
 * @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
 * fun page(request: HttpServletRequest): String = createHTML().html {
 *     dataNonce(request.cspNonce)
 *     body { ... }
 * }
 * ```
 *
 * The nonce belongs to the response that carried the document. Scripts that arrive later in an
 * element patch do not need this request's nonce and must not be given it: Datastar re-creates
 * every patched `<script>` with the nonce the page was loaded with.
 *
 * @param nonce Where a nonce comes from. Defaults to 16 random bytes from `SecureRandom`,
 *   base64 encoded.
 * @param policy The policy this nonce goes into, built from it. Defaults to
 *   [CspNonce.starterPolicy]; `null` generates the nonce and writes no header, which is what you
 *   want when Spring Security or something else in the chain already writes the policy.
 * @param reportOnly Report violations instead of enforcing the policy, by writing
 *   `Content-Security-Policy-Report-Only`. Worth a deploy or two on an existing application: the
 *   browser logs what the policy would have blocked and blocks nothing.
 */
public class CspNonceFilter
    @JvmOverloads
    constructor(
        private val nonce: () -> String = CspNonce::generate,
        private val policy: ((String) -> String)? = CspNonce::starterPolicy,
        private val reportOnly: Boolean = false,
    ) : HttpFilter() {
        private val header = if (reportOnly) CspNonce.REPORT_ONLY_HEADER else CspNonce.HEADER

        override fun doFilter(
            request: HttpServletRequest,
            response: HttpServletResponse,
            chain: FilterChain,
        ) {
            val value = CspNonce.checked(nonce())
            request.setAttribute(ATTRIBUTE, value)
            policy?.let { response.setHeader(header, it(value)) }
            chain.doFilter(request, response)
        }

        public companion object {
            /** The request attribute the nonce is stored under. */
            public const val ATTRIBUTE: String = "io.github.markusaugust.streamlord.cspNonce"
        }
    }

/**
 * This request's CSP nonce, to be passed to `dataNonce` on the `<html>` element.
 *
 * @throws IllegalStateException if [CspNonceFilter] is not in the chain. The alternative is
 *   returning an empty string, which renders a `data-nonce` Datastar refuses to start on.
 */
public val HttpServletRequest.cspNonce: String
    get() =
        getAttribute(CspNonceFilter.ATTRIBUTE) as? String
            ?: error("No CSP nonce on this request. Register a CspNonceFilter bean.")

/**
 * Generate a nonce for this exchange, store it, and write the policy header: the WebFlux half of
 * [CspNonceFilter], for a `WebFilter` you write yourself.
 *
 * ```kotlin
 * class CspNonceWebFilter : WebFilter {
 *     override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
 *         exchange.installCspNonce()
 *         return chain.filter(exchange)
 *     }
 * }
 * ```
 *
 * Streamlord ships the three lines rather than the filter because a `WebFilter` returns a
 * `Mono`, and Reactor is the one thing this module would then have to compile against. The
 * filter is yours; both ends of the nonce are still written from one variable here.
 *
 * @param nonce Where a nonce comes from. Defaults to 16 random bytes from `SecureRandom`,
 *   base64 encoded.
 * @param policy The policy this nonce goes into, built from it. `null` writes no header.
 * @param reportOnly Write `Content-Security-Policy-Report-Only` instead, which blocks nothing.
 * @return the nonce, which is also on the exchange attributes for [cspNonce] to find.
 */
public fun ServerWebExchange.installCspNonce(
    nonce: () -> String = CspNonce::generate,
    policy: ((String) -> String)? = CspNonce::starterPolicy,
    reportOnly: Boolean = false,
): String {
    val value = CspNonce.checked(nonce())
    attributes[CspNonceFilter.ATTRIBUTE] = value
    policy?.let {
        response.headers.set(
            if (reportOnly) CspNonce.REPORT_ONLY_HEADER else CspNonce.HEADER,
            it(value),
        )
    }
    return value
}

/**
 * This exchange's CSP nonce, to be passed to `dataNonce` on the `<html>` element.
 *
 * @throws IllegalStateException if nothing called [installCspNonce] for this exchange.
 */
public val ServerWebExchange.cspNonce: String
    get() =
        attributes[CspNonceFilter.ATTRIBUTE] as? String
            ?: error("No CSP nonce on this exchange. Call installCspNonce() from a WebFilter.")
