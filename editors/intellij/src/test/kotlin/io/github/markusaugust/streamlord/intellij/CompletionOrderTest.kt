package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CompletionOrderTest : BasePlatformTestCase() {
    fun `test only signals are offered after a dollar in a dsl expression`() {
        myFixture.addFileToProject("Other.kt", "fun g() { dataSignals(\"busy\" to 0, \"a\" to 1, \"END\" to 2); val alpha = 1 }")
        myFixture.configureByText("Feed.kt", "fun f() { val local = 1; dataSignals(\"count\" to 0); dataOnClick(\"\$<caret>\") }")
        val items = myFixture.completeBasic()!!
        val rendered =
            items.map { e ->
                LookupElementPresentation().also { e.renderElement(it) }.let { "${e.lookupString}|${it.itemText}|${it.typeText}" }
            }
        println("ITEMS: $rendered")
        assertEquals(listOf("\$count", "\$END", "\$a", "\$busy"), items.map { it.lookupString })
    }
}
