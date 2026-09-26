package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException

/**
 * The namespace in which new elements are conjured. HTML is the default realm; SVG and MathML
 * are older tongues that the browser will only parse when told so explicitly.
 */
public enum class ElementNamespace(public val wire: String) {
    HTML("html"),
    SVG("svg"),
    MATHML("mathml"),
    ;

    public companion object {
        public val DEFAULT: ElementNamespace = HTML

        public fun fromWire(value: String): ElementNamespace =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
                ?: throw DatastarEventValidationException("Unknown element namespace: '$value'")
    }
}
