package io.github.markusaugust.streamlord.core.application

import io.github.markusaugust.streamlord.core.port.driving.DatastarStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Asking again, while a stream is still running.
 *
 * An ordinary request is authorised once and is over in milliseconds, so nothing can change
 * underneath it. A stream is authorised once and then lives for minutes or hours, pushing patches
 * the whole time, while the reader logs out, an administrator revokes access, a subscription
 * lapses or a role changes. Nobody asks again, so the stream carries on.
 *
 * Pass one of these where the stream is opened and Streamlord asks [allows] before writing,
 * no more often than [every]:
 *
 * ```kotlin
 * call.respondDatastar(
 *     authorisation = StreamAuthorisation(every = 10.seconds) { session.isValid() },
 * ) {
 *     ticks.collect { patchSignals("tick" to it) }
 * }
 * ```
 *
 * [allows] is your own Kotlin, compiled with the rest of your application. It never reaches the
 * browser and is never named in markup. Streamlord fixes the contract and not the logic: look in
 * a database, read a cached token, or return `true` and be done.
 *
 * @property every How long a verdict stands before it is asked for again. Checking before every
 *   patch would make a chatty stream as expensive as its check, so the answer is reused for this
 *   long. The first check happens when the stream opens, before anything is written, because the
 *   cheapest place to refuse a connection is before it starts.
 * @property onRefused Run on the stream after a refusal and before it closes, so the reader can
 *   be told. Patch an element, patch a signal, `redirect("/login")`: the stream is still open
 *   here and this is the last thing that goes down it. The default says nothing and closes.
 *   It runs to the end even while the handler is being cancelled, so that the last words are
 *   never cut in half, and is therefore given [LAST_WORDS_LIMIT] and no longer.
 * @property allows The question. `false` ends the stream.
 */
public class StreamAuthorisation(
    public val every: Duration = DEFAULT_INTERVAL,
    public val onRefused: suspend DatastarStream.() -> Unit = {},
    public val allows: suspend () -> Boolean,
) {
    init {
        require(every.isPositive()) { "every must be positive; it is how long a verdict stands" }
    }

    public companion object {
        /** Long enough that a database check costs nothing per patch, short enough to matter. */
        public val DEFAULT_INTERVAL: Duration = 5.seconds

        /** How long [onRefused] may take: ample for a patch or a redirect, and then the stream closes. */
        public val LAST_WORDS_LIMIT: Duration = 5.seconds
    }
}
