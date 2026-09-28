package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.html.patchElements
import kotlinx.serialization.Serializable

/** What the form holds. The browser sends the whole store; these are the two fields that matter. */
@Serializable
public data class MusterSignals(
    val banner: String = "",
    val swords: String = "",
)

/** One field's verdict: which field, and what is wrong with it. */
public data class Fault(
    val field: String,
    val says: String,
)

private const val BANNER_MAX = 24
private const val SWORDS_MAX = 999

/**
 * The rules, in one place, on the server.
 *
 * That is the point of the demo rather than a detail of it. The same rules would have to exist
 * on the server anyway — nothing that arrives over a network may be trusted — so writing them
 * once and letting the page display what the server decided removes the copy, not the check.
 * There is no validation library here and no schema shared with the browser, because there is
 * no second implementation to keep in step with this one.
 */
public fun faults(signals: MusterSignals): List<Fault> =
    buildList {
        val banner = signals.banner.trim()
        when {
            banner.isEmpty() -> {
                add(Fault("banner", "A banner needs a name."))
            }

            banner.length < 3 -> {
                add(Fault("banner", "Three letters at least; this is a banner, not a grunt."))
            }

            banner.length > BANNER_MAX -> {
                add(Fault("banner", "No more than $BANNER_MAX characters. Heralds have to shout it."))
            }

            !banner.all { it.isLetter() || it.isWhitespace() || it == '\'' } -> {
                add(Fault("banner", "Letters and spaces only."))
            }
        }

        val swords = signals.swords.trim()
        val count = swords.toIntOrNull()
        when {
            swords.isEmpty() -> add(Fault("swords", "How many swords answer to it?"))
            count == null -> add(Fault("swords", "A number, written in digits."))
            count < 1 -> add(Fault("swords", "A banner with no swords is a bedsheet."))
            count > SWORDS_MAX -> add(Fault("swords", "More than $SWORDS_MAX is an army, and armies muster elsewhere."))
        }
    }

/**
 * Signals for the fields, elements for the summary.
 *
 * The inputs are never patched, and that is deliberate: replacing an element the reader is
 * typing into would move their caret, so what the server sends is the *text* of each fault as
 * a signal, and the page binds it — `data-text` for the message, `data-attr` for aria-invalid.
 *
 * The summary is the opposite case and is patched as markup, because it is a list that exists
 * only when something is wrong, it is built from Fristil's classes the server cannot see, and
 * it is exactly the kind of thing a server is better at deciding than a browser.
 */
public fun validationEvents(signals: MusterSignals): List<DatastarEvent> {
    val faults = faults(signals)
    val says = { field: String -> faults.firstOrNull { it.field == field }?.says.orEmpty() }

    return listOf(
        PatchSignals(
            """{"bannerFault": ${quote(says("banner"))}, """ +
                """"swordsFault": ${quote(says("swords"))}, """ +
                """"mustered": ${faults.isEmpty()}}""",
        ),
        patchElements(selector = "#muster-summary", mode = ElementPatchMode.INNER) {
            summary(faults, signals)
        },
    )
}

/** JSON string, escaped. The service ships no JSON library and needs none for this. */
private fun quote(value: String): String =
    buildString {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
