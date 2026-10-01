package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.domain.CspNonce
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.header
import io.ktor.util.AttributeKey

/** Configuration for [CspNoncePlugin]. */
public class CspNonceConfig {
    /** Where a nonce comes from. Defaults to 16 random bytes from `SecureRandom`, base64 encoded. */
    public var nonce: () -> String = CspNonce::generate

    /**
     * The policy this nonce goes into, built from it.
     *
     * Defaults to [CspNonce.starterPolicy], which is strict and small enough to break something
     * on its first run. Replace it with your own, or set it to `null` to have the plugin
     * generate the nonce and write no header at all, which is what you want when something else
     * in the chain already writes the policy.
     */
    public var policy: ((String) -> String)? = CspNonce::starterPolicy

    /**
     * Report violations instead of enforcing the policy, by writing
     * `Content-Security-Policy-Report-Only`.
     *
     * Worth a deploy or two on an existing application: the browser logs what the policy would
     * have blocked and blocks nothing, so you find the inline script you forgot before your
     * readers do.
     */
    public var reportOnly: Boolean = false
}

internal val CspNonceKey: AttributeKey<String> = AttributeKey<String>("StreamlordCspNonce")

/**
 * Generates one CSP nonce per call, writes the policy header from it, and hands the same value
 * to your markup through [cspNonce]. Both ends come from one variable, so they cannot drift.
 *
 * ```kotlin
 * install(CspNoncePlugin)
 *
 * routing {
 *     get("/") {
 *         val nonce = call.cspNonce
 *         call.respondText(contentType = ContentType.Text.Html) {
 *             createHTML().html {
 *                 dataNonce(nonce)
 *                 body { ... }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * The nonce belongs to the response that carried the document. Scripts that arrive later in an
 * element patch do not need this call's nonce and must not be given it: Datastar re-creates every
 * patched `<script>` with the nonce the page was loaded with.
 */
public val CspNoncePlugin: ApplicationPlugin<CspNonceConfig> =
    createApplicationPlugin("StreamlordCspNonce", ::CspNonceConfig) {
        val generate = pluginConfig.nonce
        val policy = pluginConfig.policy
        val header = if (pluginConfig.reportOnly) CspNonce.REPORT_ONLY_HEADER else CspNonce.HEADER

        onCall { call ->
            val nonce = CspNonce.checked(generate())
            call.attributes.put(CspNonceKey, nonce)
            policy?.let { call.response.header(header, it(nonce)) }
        }
    }

/**
 * This call's CSP nonce, to be passed to `dataNonce` on the `<html>` element.
 *
 * @throws IllegalStateException if [CspNoncePlugin] is not installed. The alternative is
 *   returning an empty string, which renders a `data-nonce` Datastar refuses to start on.
 */
public val ApplicationCall.cspNonce: String
    get() =
        attributes.getOrNull(CspNonceKey)
            ?: error("No CSP nonce on this call. Add install(CspNoncePlugin) to the application.")
