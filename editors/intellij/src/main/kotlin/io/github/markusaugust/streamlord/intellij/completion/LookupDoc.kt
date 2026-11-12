package io.github.markusaugust.streamlord.intellij.completion

/**
 * The object of a lookup item: its [rank] in the order the analysis gave, and its documentation in
 * Markdown, shown with F1 or Ctrl+Q while the list is open.
 */
class LookupDoc(
    val name: String,
    val markdown: String?,
    val rank: Int,
)
