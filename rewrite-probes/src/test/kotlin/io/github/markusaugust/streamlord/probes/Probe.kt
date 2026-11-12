package io.github.markusaugust.streamlord.probes

import io.github.markusaugust.streamlord.core.domain.Trusted

/** One function as written, and as each fix of the analysis leaves it; the generator fills these in. */
class Probe(
    val name: String,
    val source: String,
    /** The type of each parameter, as written. */
    val types: List<String>,
    /** What the case says of the rewrite: `preferred`, `offered`, `none`, or null where it says nothing. */
    val rewrite: String?,
    /** What some message of the case must hold. */
    val says: List<String>,
    /** The values an issue must mark. */
    val marks: List<String>,
    val issues: List<Said>,
    val before: (List<Any?>) -> Any?,
    val variants: List<Variant>,
)

class Said(
    val text: String,
    val message: String,
)

class Variant(
    val title: String,
    val preferred: Boolean,
    /** Whether it is the context action and not the fix of an issue. */
    val context: Boolean,
    val source: String,
    val run: (List<Any?>) -> Any?,
)

/** What the probes' parameters are typed as, beside Kotlin's own and core's. */
class Widget(
    val text: String,
) {
    override fun toString(): String = text
}

class User(
    val name: String,
    val tags: List<String>,
    val badge: Trusted,
)

class WidgetContext(
    val rootId: String,
    val contentId: String,
    val eventsId: String,
)

/** A Trusted the text of a probe does not show to be one. */
typealias Badge = Trusted
