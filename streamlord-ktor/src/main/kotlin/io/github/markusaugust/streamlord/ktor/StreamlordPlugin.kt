package io.github.markusaugust.streamlord.ktor

import io.github.markusaugust.streamlord.core.application.Streamlord
import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey

/**
 * Configuration for the [StreamlordPlugin]. Either hand over a fully built [streamlord], or set
 * [codec] and [maxSignalsLength] and let the plugin build one.
 */
public class StreamlordPluginConfig {
    /** A pre-built instance. Takes precedence over the other properties when set. */
    public var streamlord: Streamlord? = null

    /** Codec for typed signals. Defaults to the dependency-free built-in codec. */
    public var codec: SignalsCodec? = null

    /** Upper bound for incoming signal payloads: bytes for bodies, characters for the query parameter. */
    public var maxSignalsSize: Int = Streamlord.DEFAULT_MAX_SIGNALS_SIZE

    /**
     * Run [ElementsGuard] over every element patch and elements response, for HTML written as
     * strings or rendered by a template engine. It throws `InterpolatedExpressionException` for
     * an eaten expression and `MistypedAttributeException` for a `data-*` name one letter from a
     * Datastar one; both are `StreamlordException`s.
     */
    public var guardElements: Boolean = false

    /** The `data-*` prefixes the guard recognises; `data-` and `data-star-` unless you alias the bundle. */
    public var attributePrefixes: List<String> = ElementsGuard.defaultPrefixes

    internal fun build(): Streamlord =
        streamlord ?: Streamlord(
            codec = codec ?: Streamlord.Default.codec,
            maxSignalsSize = maxSignalsSize,
            guardElements = guardElements,
            attributePrefixes = attributePrefixes,
        )
}

internal val StreamlordKey: AttributeKey<Streamlord> = AttributeKey("Streamlord")

/**
 * Installs a configured [Streamlord] into the application, so every `call.respondDatastar { }`
 * and `call.readSignals()` in the realm speaks with the same codec and limits.
 *
 * ```kotlin
 * install(StreamlordPlugin) {
 *     codec = KotlinxSignalsCodec()
 * }
 * ```
 *
 * The plugin is optional. Without it, the extension functions fall back to [Streamlord.Default].
 */
public val StreamlordPlugin: io.ktor.server.application.ApplicationPlugin<StreamlordPluginConfig> =
    createApplicationPlugin("Streamlord", ::StreamlordPluginConfig) {
        application.attributes.put(StreamlordKey, pluginConfig.build())
    }

/** The [Streamlord] installed on this application, or [Streamlord.Default]. */
public val ApplicationCall.streamlord: Streamlord
    get() = application.attributes.getOrNull(StreamlordKey) ?: Streamlord.Default
