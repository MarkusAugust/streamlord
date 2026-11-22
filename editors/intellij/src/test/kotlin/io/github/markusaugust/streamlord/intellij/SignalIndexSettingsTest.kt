package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import io.github.markusaugust.streamlord.intellij.index.SignalIndex

/** Spring's settings say how the codec names signals: a naming strategy there leaves every class's names to it, as the analysis reads it. */
class SignalIndexSettingsTest : StreamlordTestCase() {
    fun `test a naming strategy in Spring's settings renames the classes, and other settings say nothing`() {
        myFixture.addFileToProject("src/main/resources/logback.yml", "spring.jackson.property-naming-strategy: SNAKE_CASE\n")
        val settings = myFixture.addFileToProject("src/main/resources/application.yml", "server:\n  port: 8080\n")
        val page = myFixture.configureByText("page.html", "<div data-signals:open=\"false\"></div>")
        assertEquals(setOf("open"), SignalIndex.getInstance(project).definitionsSeenFrom(page))
        val documents = PsiDocumentManager.getInstance(project)
        WriteCommandAction.runWriteCommandAction(project) {
            documents.getDocument(settings)!!.setText("spring:\n  jackson:\n    property-naming-strategy: SNAKE_CASE\n")
            documents.commitAllDocuments()
        }
        assertEquals(setOf("open", "renamed:classes"), SignalIndex.getInstance(project).definitionsSeenFrom(page))
    }
}
