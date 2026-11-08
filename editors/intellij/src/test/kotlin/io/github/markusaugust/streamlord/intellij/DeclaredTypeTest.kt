package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import io.github.markusaugust.streamlord.analysis.isMarkup
import io.github.markusaugust.streamlord.intellij.analysis.HoleFacts
import org.jetbrains.kotlin.psi.KtFile

/**
 * Whether a value is Streamlord's markup by the type it is declared with, in the mode the test JVM
 * runs the Kotlin plugin in: K2 under `test`, K1 under `testK1`. Each resolves the type, so an
 * `Html` the file imports from another package is no markup.
 */
class DeclaredTypeTest : StreamlordTestCase() {
    private fun markup(text: String): Boolean {
        myFixture.addFileToProject("com/acme/Html.kt", "package com.acme\n\nclass Html\n\nclass Markup\n")
        val file = myFixture.configureByText("Page.kt", text) as KtFile
        val facts =
            ApplicationManager
                .getApplication()
                .executeOnPooledThread<Map<String, Boolean>> {
                    ReadAction
                        .nonBlocking<Map<String, Boolean>> {
                            HoleFacts.of(file).entries.associate { (range, fact) -> range.substring(file.text) to fact.isMarkup() }
                        }.executeSynchronously()
                }.get()
        return facts["\$h"] == true
    }

    fun `test Streamlord's Html is markup`() {
        assertTrue(markup("package p\n\nimport io.github.markusaugust.streamlord.core.domain.Html\n\nfun f(h: Html) = \"<p>\$h</p>\"\n"))
    }

    fun `test an Html imported from another package is no markup`() {
        assertFalse(markup("package p\n\nimport com.acme.Html\n\nfun f(h: Html) = \"<p>\$h</p>\"\n"))
    }

    fun `test a type imported as Html is no markup`() {
        assertFalse(markup("package p\n\nimport com.acme.Markup as Html\n\nfun f(h: Html) = \"<p>\$h</p>\"\n"))
    }
}
