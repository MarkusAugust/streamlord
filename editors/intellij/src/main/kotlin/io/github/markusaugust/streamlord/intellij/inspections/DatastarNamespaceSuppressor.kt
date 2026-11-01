package io.github.markusaugust.streamlord.intellij.inspections

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.lang.html.HTMLLanguage
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis

/**
 * IntelliJ reads the colon in `data-on:click` as a namespace prefix and warns that `data-on` is
 * not bound. HTML has no namespace prefixes, so for a Datastar plugin's key the warning is
 * silenced there. XHTML keeps it: a parser that reads the page as XML stops at the prefix.
 */
class DatastarNamespaceSuppressor :
    InspectionSuppressor,
    DumbAware {
    override fun isSuppressedFor(
        element: PsiElement,
        toolId: String,
    ): Boolean {
        if (toolId != UNBOUND_PREFIX) return false
        val attribute = PsiTreeUtil.getParentOfType(element, XmlAttribute::class.java, false) ?: return false
        if (!attribute.containingFile.language.isKindOf(HTMLLanguage.INSTANCE)) return false
        val analysis = StreamlordAnalysis.getInstance(attribute.project)
        // A key in the wrong place, `data-on__window:click`, is Streamlord's own check to report.
        val name = attribute.name.lowercase()
        return ':' in name && analysis.catalog.parseAttributeName(name, analysis.prefix.lowercase())?.spec != null
    }

    override fun getSuppressActions(
        element: PsiElement?,
        toolId: String,
    ): Array<SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY

    private companion object {
        const val UNBOUND_PREFIX = "XmlUnboundNsPrefix"
    }
}
