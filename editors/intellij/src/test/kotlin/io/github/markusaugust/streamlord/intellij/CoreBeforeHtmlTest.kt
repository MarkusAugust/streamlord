package io.github.markusaugust.streamlord.intellij

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.markusaugust.streamlord.intellij.analysis.ProjectLibraries
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary
import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection

private const val CARD = "fun card(name: String): String = \"<p>\$name</p>\""

/** A project on a core from before 0.16.0: `Trusted` and `interpolate` are there, `Html` is not, and no message sends the author to it. */
class CoreBeforeHtmlTest : BasePlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = CoreBeforeHtmlProject

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KotlinInterpolationInspection())
    }

    fun `test markup the author built is sent to Trusted alone`() {
        val file = myFixture.configureByText("Card.kt", CARD)
        assertEquals(true, ProjectLibraries.known(file, StreamlordLibrary.CORE))
        val shown = myFixture.doHighlighting().single { it.inspectionToolId == "KotlinInterpolationInspection" }.description
        assertTrue(shown, shown.contains("wrap it in Trusted(...)"))
        assertFalse(shown, shown.contains("Html"))
    }
}

/** Today's core has `Html`, and the message names it. */
class CoreWithHtmlTest : StreamlordTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(KotlinInterpolationInspection())
    }

    fun `test markup the author built is sent to Html or Trusted`() {
        myFixture.configureByText("Card.kt", CARD)
        val shown = myFixture.doHighlighting().single { it.inspectionToolId == "KotlinInterpolationInspection" }.description
        assertTrue(shown, shown.contains("declare it Html or wrap it in Trusted(...)"))
    }
}
