package io.github.markusaugust.streamlord.intellij

import io.github.markusaugust.streamlord.intellij.index.SignalIndex

/** A Kotlin template before an equals sign is a Kotlin value: the index takes it for no signal, where an escaped dollar assigns one. */
class SignalIndexTemplateTest : StreamlordTestCase() {
    fun `test a template before an equals sign defines no signal`() {
        val page =
            myFixture.configureByText(
                "Row.kt",
                "fun row(v: String, name: String, value: String) = \"<p \$v=\\\"a\\\" data-on:click=\\\"\\\$kept = 1\\\">\$name=\$value</p>\"",
            )
        val defined = SignalIndex.getInstance(project).definitionsSeenFrom(page)
        assertEquals(setOf("kept"), defined.intersect(setOf("v", "name", "kept")))
    }
}
