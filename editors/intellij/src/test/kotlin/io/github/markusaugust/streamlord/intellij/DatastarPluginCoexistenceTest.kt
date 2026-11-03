package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiDocumentManager
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
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext

/** The official plugin's signal element: a declaration in the same file, named by its last part, which renames itself. */
class OfficialElement(
    private val f: PsiFile,
    private val start: Int,
    private val path: String,
) : FakePsiElement(),
    PsiNamedElement {
    override fun getParent() = f

    override fun getContainingFile() = f

    override fun getName() = path.substringAfterLast('.')

    override fun getTextRange() = TextRange(start, start + name.length)

    override fun getTextOffset() = start

    override fun setName(name: String): PsiNamedElement {
        val manager = PsiDocumentManager.getInstance(project)
        val document = manager.getDocument(f) ?: return this
        manager.doPostponedOperationsAndUnblockDocument(document)
        document.replaceString(start, start + this.name.length, name)
        manager.commitDocument(document)
        return this
    }

    override fun toString() = "Official($path@$start)"
}

/** The official plugin's reference: one part of a read, resolved to `data-signals:<path>` in the same file. */
class OfficialReference(
    v: XmlAttributeValue,
    r: TextRange,
    private val path: String,
) : PsiReferenceBase<XmlAttributeValue>(v, r, false) {
    override fun resolve(): PsiElement? {
        val text = element.containingFile.text
        val at = Regex("data-signals:" + Regex.escape(path) + "(?![\\w.-])").find(text) ?: return null
        val last = path.substringAfterLast('.')
        return OfficialElement(element.containingFile, at.range.last + 1 - last.length, path)
    }

    override fun handleElementRename(newElementName: String) =
        ElementManipulators.handleContentChange(element, rangeInElement, newElementName)
}

/** Its contributor, which reads `$a.b` part by part in the value of a `data-*` expression attribute. */
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
        val attribute = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        if (!attribute.name.startsWith("data-") || attribute.name.startsWith("data-signals")) return PsiReference.EMPTY_ARRAY
        val contentOffset = value.text.indexOf(value.value).coerceAtLeast(0)
        return Regex("\\$([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*)")
            .findAll(value.value)
            .flatMap { match ->
                val segments = match.groupValues[1].split('.')
                var segmentStart = match.range.first + 1
                segments.mapIndexed { index, segment ->
                    val range = TextRange(contentOffset + segmentStart, contentOffset + segmentStart + segment.length)
                    segmentStart += segment.length + 1
                    OfficialReference(value, range, segments.take(index + 1).joinToString("."))
                }
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
        val targets =
            GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset).map {
                it.containingFile.name to
                    it.textOffset
            }
        assertEquals(listOf("Handler.kt" to handler.text.indexOf("count"), "page.html" to 18), targets.sortedBy { it.first })
        myFixture.renameElementAtCaretUsingHandler("total")
        assertEquals("<div data-signals:total=\"0\"><span data-text=\"\$total\"></span></div>", myFixture.file.text)
        assertTrue(handler.text, "\"total\" to 1" in handler.text)
    }

    fun `test a signal its own page defines is resolved, listed once and found`() {
        myFixture.addFileToProject(
            "View.kt",
            "import io.github.markusaugust.streamlord.html.*\nfun f() = span { dataText(\"\${'\$'}count\") }",
        )
        myFixture.configureByText("page.html", "<div data-signals:count=\"0\"><span data-text=\"\$co<caret>unt\"></span></div>")
        myFixture.enableInspections(com.intellij.codeInsight.daemon.impl.analysis.XmlUnresolvedReferenceInspection::class.java)
        assertEmpty(myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR))
        val listed = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
        assertEquals(listOf("page.html" to 18), listed.map { it.containingFile.name to it.textOffset })
        // Find usages may start from either plugin's element, and finds every place.
        val reference = TargetElementUtil.findReference(myFixture.editor, myFixture.caretOffset)!!
        for (target in TargetElementUtil.getInstance().getTargetCandidates(reference)) {
            assertEquals(
                target.toString(),
                listOf("View.kt", "page.html", "page.html"),
                myFixture.findUsages(target).map { it.file!!.name }.sorted(),
            )
        }
    }

    fun `test a dotted key is listed once`() {
        myFixture.addFileToProject(
            "Handler.kt",
            "import io.github.markusaugust.streamlord.html.*\nfun h() = patchSignals(mapOf(\"user.name\" to 1))",
        )
        myFixture.configureByText("page.html", "<div data-signals:user.name=\"0\"><span data-text=\"\$user.na<caret>me\"></span></div>")
        val listed = GotoDeclarationAction.findAllTargetElements(project, myFixture.editor, myFixture.caretOffset)
        assertEquals(listOf("Handler.kt", "page.html"), listed.map { it.containingFile.name }.sorted())
    }
}
