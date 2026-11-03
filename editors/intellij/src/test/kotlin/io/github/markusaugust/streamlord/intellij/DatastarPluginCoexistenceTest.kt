package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.impl.source.resolve.reference.PsiReferenceContributorEP
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext

/** The official plugin's signal element, a declaration in the same file. */
class OfficialElement(
    private val f: PsiFile,
    private val start: Int,
    private val n: String,
) : FakePsiElement(),
    PsiNamedElement {
    override fun getParent() = f

    override fun getContainingFile() = f

    override fun getName() = n

    override fun getTextRange() = TextRange(start, start + n.length)

    override fun getTextOffset() = start

    override fun setName(name: String): PsiNamedElement = this

    override fun toString() = "Official($n@$start)"
}

class OfficialReference(
    v: XmlAttributeValue,
    r: TextRange,
    private val path: String,
) : PsiReferenceBase<XmlAttributeValue>(v, r, false) {
    override fun resolve(): PsiElement? {
        val text = element.containingFile.text
        val at = Regex("data-signals:" + Regex.escape(path) + "\\b").find(text) ?: return null
        return OfficialElement(element.containingFile, at.range.first + "data-signals:".length, path)
    }

    override fun handleElementRename(newElementName: String) =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)
}

class OfficialContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttributeValue::class.java), OfficialProvider)
    }
}

object OfficialProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val value = element as XmlAttributeValue
        val contentOffset = value.text.indexOf(value.value).coerceAtLeast(0)
        return Regex("\\$([A-Za-z0-9_-]+)")
            .findAll(value.value)
            .map {
                OfficialReference(
                    value,
                    TextRange(contentOffset + it.range.first + 1, contentOffset + it.range.last + 1),
                    it.groupValues[1],
                )
            }.toList()
            .toTypedArray()
    }
}

/**
 * Beside the official Datastar plugin, whose references find a signal declared in the same HTML file:
 * its reference contributor is copied here in the shape its sources have, since the plugin is no
 * dependency of these tests.
 */
class DatastarPluginCoexistenceTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val ep = PsiReferenceContributorEP()
        ep.language = "XML"
        ep.implementationClass = OfficialContributor::class.java.name
        ep.pluginDescriptor = PluginManagerCore.getPlugin(PluginId.getId("io.github.markusaugust.streamlord"))!!
        PsiReferenceContributor.EP_NAME.point.registerExtension(ep, testRootDisposable)
    }

    fun `test go to declaration lists a place once, and rename is Streamlord's`() {
        val handler =
            myFixture.addFileToProject(
                "Handler.kt",
                "import io.github.markusaugust.streamlord.html.*\nfun h() = patchSignals(mapOf(\"count\" to 1))",
            )
        myFixture.configureByText("page.html", "<div data-signals:count=\"0\"><span data-text=\"\$co<caret>unt\"></span></div>")
        val reference = TargetElementUtil.findReference(myFixture.editor, myFixture.caretOffset)!!
        assertTrue(reference.toString(), reference.toString().contains("OfficialReference"))
        val targets = TargetElementUtil.getInstance().getTargetCandidates(reference).map { it.containingFile.name to it.textOffset }
        assertEquals(listOf("Handler.kt" to handler.text.indexOf("count"), "page.html" to 18), targets.sortedBy { it.first })
        myFixture.renameElementAtCaretUsingHandler("total")
        assertEquals("<div data-signals:total=\"0\"><span data-text=\"\$total\"></span></div>", myFixture.file.text)
        assertTrue(handler.text, "\"total\" to 1" in handler.text)
    }
}
