package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.domain.PatchElements
import io.github.markusaugust.streamlord.core.domain.PatchSignals
import io.github.markusaugust.streamlord.html.patchElements
import kotlinx.serialization.Serializable

/**
 * What a press did to whatever the reader typed, which is the one thing the demo is about.
 *
 * [color] is the Fristil alert colour: success when the field survived, warning when it did not.
 */
public data class Verdict(
    val color: String,
    val title: String,
    val says: String,
)

private val REBUILT = Verdict("info", "Rebuilt", "The wall is as it started, with an empty field in the stone.")

/** The verdict for each mode, in the words the reader sees beside the wall. */
internal fun verdict(mode: ElementPatchMode): Verdict =
    when (mode) {
        ElementPatchMode.INNER -> {
            Verdict(
                "success",
                "Kept",
                "Inner morphed the inside of the stone. The field is the same element, so what you typed is still in it.",
            )
        }

        ElementPatchMode.OUTER -> {
            Verdict(
                "success",
                "Kept",
                "Outer morphed the whole stone. A morph changes only what differs, so the field and what you typed survived.",
            )
        }

        ElementPatchMode.REPLACE -> {
            Verdict(
                "warning",
                "Gone",
                "Replace threw the stone away and set a new one in its place, with an empty field. " +
                    "Type again and press Outer to compare.",
            )
        }

        ElementPatchMode.PREPEND, ElementPatchMode.APPEND -> {
            Verdict(
                "success",
                "Untouched",
                "${mode.label} laid a course inside the wall and never touched the stone, so what you typed is where you left it.",
            )
        }

        ElementPatchMode.BEFORE, ElementPatchMode.AFTER -> {
            Verdict(
                "success",
                "Untouched",
                "${mode.label} set a course beside the stone and never touched it, so what you typed is where you left it.",
            )
        }

        ElementPatchMode.REMOVE -> {
            Verdict("warning", "Gone", "Remove took the stone away, field and all. Rebuild the wall to get it back.")
        }
    }

private val ElementPatchMode.label: String get() = wire.replaceFirstChar { it.uppercase() }

/** Which of the eight the reader pressed. */
@Serializable
public data class ModeSignals(
    val mode: String = "inner",
)

/**
 * The eight patch modes, each against the element it makes sense against.
 *
 * `prepend` and `append` put elements inside the target, so they act on the wall. `before` and
 * `after` put them beside it, so they act on the stone. `remove` takes the stone away and needs
 * no elements at all, only a selector, which is the one thing the table in the protocol page
 * cannot show you.
 *
 * The stone carries an input, and that is the whole reason this is worth building. `outer` and
 * `inner` morph: type into the input, press either, and what you typed is still there while the
 * text around it changes. `replace` does not morph, and the same press throws it away. That is
 * the difference between the two halves of the table, and it is invisible in prose.
 */
public fun modeEvents(signals: ModeSignals): List<DatastarEvent> {
    /*
     * Rebuilding the wall is itself a patch (`inner` on the wall with a fresh stone) so the
     * reset button is not an exception to the demo but the ninth example in it.
     */
    if (signals.mode == "reset") {
        return listOf(
            patchElements(selector = "#wall", mode = ElementPatchMode.INNER) {
                stone("A stone in the wall. Aim the eight modes at it.")
            },
            patchElements(selector = "#modes-said", mode = ElementPatchMode.INNER) {
                said(REBUILT, ElementPatchMode.INNER, "#wall", "the wall, as it started")
            },
            PatchSignals("""{"lastMode": "reset"}"""),
        )
    }

    val mode = ElementPatchMode.entries.firstOrNull { it.wire == signals.mode } ?: ElementPatchMode.INNER

    val patch =
        when (mode) {
            ElementPatchMode.INNER -> {
                patchElements(selector = "#stone", mode = mode) {
                    stoneInside("Inner: the paragraph is the same element, its contents are not.")
                }
            }

            ElementPatchMode.OUTER -> {
                patchElements(selector = "#stone", mode = mode) {
                    stone("Outer: the whole paragraph was morphed, and what you typed survived.")
                }
            }

            ElementPatchMode.REPLACE -> {
                patchElements(selector = "#stone", mode = mode) {
                    stone("Replace: a new paragraph stands here. Whatever you typed is gone.")
                }
            }

            ElementPatchMode.PREPEND -> {
                patchElements(selector = "#wall", mode = mode) {
                    course("Prepend: laid as the first course inside the wall.")
                }
            }

            ElementPatchMode.APPEND -> {
                patchElements(selector = "#wall", mode = mode) {
                    course("Append: laid as the last course inside the wall.")
                }
            }

            ElementPatchMode.BEFORE -> {
                patchElements(selector = "#stone", mode = mode) {
                    course("Before: set down beside the stone, above it.")
                }
            }

            ElementPatchMode.AFTER -> {
                patchElements(selector = "#stone", mode = mode) {
                    course("After: set down beside the stone, below it.")
                }
            }

            // No elements at all: the event is a selector and a mode. The DSL's patchElements
            // always writes markup, so this one is the core type directly, which is the honest
            // shape of a remove.
            ElementPatchMode.REMOVE -> {
                PatchElements(selector = "#stone", mode = mode)
            }
        }

    /*
     * What was sent, in the words the protocol uses. The page shows this beside the wall, so a
     * reader can see the selector and the mode that produced what they are looking at, and
     * that `remove` carried no elements, because it needs none.
     */
    val said =
        patchElements(selector = "#modes-said", mode = ElementPatchMode.INNER) {
            said(verdict(mode), mode, patch.selector, patch.elements.orEmpty())
        }

    return listOf(patch, said, PatchSignals("""{"lastMode": "${mode.wire}"}"""))
}
