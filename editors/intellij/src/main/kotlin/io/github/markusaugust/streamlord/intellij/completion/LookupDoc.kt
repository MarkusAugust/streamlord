package io.github.markusaugust.streamlord.intellij.completion

/** The object of a lookup item that carries its documentation, shown with F1 or Ctrl+Q while the list is open. */
class LookupDoc(
    val name: String,
    val html: String,
)
