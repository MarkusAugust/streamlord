package io.github.markusaugust.streamlord.analysis

/*
 * Where a caret stands, for completion, hover and parameter info; shared with completionContext.ts.
 */

/** The attribute value a caret stands in: the attribute's name as written, where its value starts, and the value before the caret, decoded. */
public data class ValueAtCaret(
    val attribute: String,
    val valueStart: Int,
    val before: String,
)

/** The quoted attribute value [offset] stands in, in [markup] read as the browser reads it, or null. */
public fun valueAtCaret(
    markup: String,
    offset: Int,
    prefix: String,
): ValueAtCaret? = null

/** The action call a caret stands in: the action's name, which argument, and the key of the options object when the caret is on one. */
public data class ActionCall(
    val action: String,
    val argument: Int,
    val option: OptionKeyAt?,
)

/** A key of a backend action's options object at the caret: what is typed of it, and the keys the object already holds. */
public data class OptionKeyAt(
    val typed: String,
    val written: List<String>,
)

/** The innermost `@name(` still open at [offset] in the expression [text], read from the text before the caret, or null. */
public fun actionCallAt(
    text: String,
    offset: Int,
): ActionCall? = null
