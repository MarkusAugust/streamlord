package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext
import io.github.markusaugust.streamlord.analysis.SignalDeclaration
import io.github.markusaugust.streamlord.analysis.SignalSite as AnalysedSite
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * A reference on each `$name` an expression reads and on each place a signal is defined, so that
 * go to declaration, find usages and rename know signals. A Kotlin string holds all of its own: the
 * HTML injected into it drops what `${'$'}` writes, so its offsets are not the file's.
 */
class SignalReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(KtStringTemplateExpression::class.java), SignalReferenceProvider)
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttributeValue::class.java), SignalReferenceProvider)
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttribute::class.java), SignalReferenceProvider)
    }
}

private object SignalReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        // An attribute holds its value, which has references of its own: the attribute keeps to its name.
        val range = (element as? XmlAttribute)?.nameElement?.textRange ?: element.textRange
        // A string inside a template of this one holds its own references.
        val host = if (element is KtStringTemplateExpression) element.textRange.startOffset else -1
        return SignalSites
            .of(file)
            .filter { it.start >= range.startOffset && it.end <= range.endOffset && it.host == host }
            .map { SignalPsiReference(element, it.start, it.end, it.signal, it.declaration) }
            .toTypedArray()
    }
}

/**
 * A signal read or defined from [start] to [end] in [element]'s file. A read covers one part of its
 * path, the last part of [signal]: `name` in `$user.name` for `user.name`. A defining place covers
 * the name as written, and [declaration] says how a new one is written there.
 */
class SignalPsiReference(
    element: PsiElement,
    private val start: Int,
    private val end: Int,
    val signal: String,
    private val declaration: SignalDeclaration? = null,
) : PsiPolyVariantReferenceBase<PsiElement>(element) {
    override fun calculateDefaultRangeInElement(): TextRange {
        val base = element.textRange.startOffset
        return TextRange(start - base, end - base)
    }

    /**
     * What goes in place of the reference's range when the signal [old] is renamed [newName], or
     * null when nothing written here can name it.
     */
    fun renameText(
        old: String,
        newName: String,
    ): String? = AnalysedSite(start, end, signal, declaration).renameText(element.containingFile.text, old, newName)

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        SignalTarget.of(element.containingFile, signal).map { PsiElementResolveResult(it) }.toTypedArray()

    override fun isReferenceTo(element: PsiElement): Boolean = element is SignalTarget && element.name == signal

    override fun getVariants(): Array<Any> = emptyArray()

    /** A defining place is no read: it is where go to declaration lands, and where find usages starts. */
    val defines: Boolean get() = declaration != null
}
