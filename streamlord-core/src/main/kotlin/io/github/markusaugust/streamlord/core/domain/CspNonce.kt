package io.github.markusaugust.streamlord.core.domain

import java.security.SecureRandom
import java.util.Base64

/**
 * The nonce that turns Datastar's CSP mode on, and the header it has to agree with.
 *
 * Datastar compiles attribute expressions with `new Function`, which a strict `script-src`
 * blocks. A `data-nonce` on the `<html>` element switches the client to compiling through
 * nonce-bearing script elements instead, and the value in that attribute has to be the value in
 * the response's `Content-Security-Policy` header. When the two drift apart nothing announces
 * it: the client stays on `new Function` and the policy blocks every expression on the page.
 *
 * This object is the half of the job that is the same in every framework. The adapters own the
 * other half: `CspNoncePlugin` in streamlord-ktor and `CspNonceFilter` in streamlord-spring
 * generate one nonce per response and write both ends from it, so they cannot disagree.
 *
 * Streamlord owns the nonce. It does not own your policy: image sources, fonts and analytics are
 * yours, and [starterPolicy] is offered as somewhere to start rather than as the answer.
 */
public object CspNonce {
    /** The header a policy is enforced from. */
    public const val HEADER: String = "Content-Security-Policy"

    /** The header a policy is only reported from, which blocks nothing. */
    public const val REPORT_ONLY_HEADER: String = "Content-Security-Policy-Report-Only"

    /** The name of the attribute the nonce goes in, on the `<html>` element. */
    public const val ATTRIBUTE: String = "data-nonce"

    private const val BYTES = 16

    // Section 2.3.1 of CSP Level 3: a nonce-value is a base64-value, padding included.
    private val BASE64 = Regex("[A-Za-z0-9+/\\-_]+={0,2}")

    private val random = SecureRandom()

    /**
     * A fresh nonce: 16 random bytes from [SecureRandom], base64 encoded.
     *
     * A nonce is per response, not per session and not per application. Reusing one across
     * responses turns it into a constant, and a constant an attacker can read out of one page
     * and paste into the next is not a nonce.
     */
    public fun generate(): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * A policy to start from: same-origin everything, scripts from this origin or carrying
     * [nonce], no plugins, and a `<base>` that cannot be moved off the origin.
     *
     * It is deliberately strict and deliberately small, so the first thing it does in a real
     * application is break something you then have to name. Replace it with your own as soon as
     * you know what the page actually loads.
     */
    public fun starterPolicy(nonce: String): String =
        "default-src 'self'; script-src 'self' 'nonce-$nonce'; object-src 'none'; base-uri 'self'"

    /**
     * Return [nonce] if it can survive the trip into both a header and an HTML attribute.
     *
     * Only reached when you supply your own generator; [generate] always passes. An empty nonce
     * is rejected here rather than in the browser, where Datastar raises `NonceRequired` and the
     * page stops.
     *
     * @throws IllegalArgumentException if the nonce is empty or is not a CSP base64-value.
     */
    public fun checked(nonce: String): String {
        require(nonce.isNotEmpty()) {
            "The CSP nonce is empty. Datastar raises NonceRequired for an empty data-nonce and does not start."
        }
        require(BASE64.matches(nonce)) {
            "'$nonce' is not a valid CSP nonce; it must be a base64 value, as CSP Level 3 section 2.3.1 defines one"
        }
        return nonce
    }
}
