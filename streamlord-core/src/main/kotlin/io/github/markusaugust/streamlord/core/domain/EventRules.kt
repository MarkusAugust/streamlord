package io.github.markusaugust.streamlord.core.domain

import io.github.markusaugust.streamlord.core.DatastarEventValidationException
import io.github.markusaugust.streamlord.core.InternalStreamlordApi
import kotlin.time.Duration

/**
 * What the events and the responses refuse in one argument, run by the same code they run, for
 * the analysis that reads a literal argument before it does, so the two say the same thing. Each
 * answers the message core throws with, or null where core takes the value.
 */
@InternalStreamlordApi
public object EventRules {
    /** A selector, or the one named [field]: blank, or over more than one line. */
    public fun selector(
        value: String,
        field: String = "selector",
    ): String? = refusal { Wire.selector(value, field) }

    /** A selector an answer that is no stream sends as a header: printable ASCII only. */
    public fun headerSelector(value: String): String? = refusal { Wire.headerSelector(value) }

    public fun eventId(value: String): String? = refusal { Wire.eventId(value) }

    public fun retry(value: Duration): String? = refusal { Wire.retry(value) }

    /** Signals as JSON text: one object, with no key the client cannot hold. */
    public fun signals(json: String): String? = refusal { Wire.signals(json) }

    public fun script(script: String): String? = refusal { ExecuteScript(script, autoRemove = false) }

    public fun redirect(url: String): String? = refusal { ExecuteScript.redirect(url) }

    /** Why a patch in [mode] without a selector is refused, or null where the mode needs none. */
    public fun modeWithoutSelector(mode: ElementPatchMode): String? =
        if (mode.requiresSelector) "Patch mode '${mode.wire}' requires a selector" else null

    /**
     * Why [html] is refused as the elements of a patch in [mode] where it is white space as HTML counts
     * it, which every reading of it takes for nothing; null for other markup, judged apart.
     */
    public fun elements(
        html: String,
        mode: ElementPatchMode,
        response: Boolean,
    ): String? = if (Wire.htmlBlank(html)) blankElements(mode, response) else null

    /**
     * Why elements that are white space as HTML counts it are refused in [mode], by an event or, with
     * [response], by an answer that is no stream; null in `inner`, which they empty, and in `remove`.
     */
    internal fun blankElements(
        mode: ElementPatchMode,
        response: Boolean,
    ): String? =
        when {
            mode == ElementPatchMode.INNER || mode == ElementPatchMode.REMOVE -> null
            response -> "elements must not be blank"
            else -> "Patch mode '${mode.wire}' requires elements"
        }

    private inline fun refusal(check: () -> Unit): String? =
        try {
            check()
            null
        } catch (e: DatastarEventValidationException) {
            e.message
        } catch (e: IllegalArgumentException) {
            e.message
        }
}
