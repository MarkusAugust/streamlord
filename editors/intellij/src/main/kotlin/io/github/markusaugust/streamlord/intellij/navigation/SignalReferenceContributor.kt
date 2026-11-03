package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import org.jetbrains.kotlin.psi.KtFile
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
        val reads =
            when {
                file is KtFile -> {
                    StreamlordAnalysis.getInstance(file.project).kotlinSignalReferences(file)
                }

                StreamlordAnalysis.isHtmlSide(file) && !InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file) -> {
                    StreamlordAnalysis.getInstance(file.project).htmlSignalReferences(file)
                }

                else -> {
                    return PsiReference.EMPTY_ARRAY
                }
            }
        // An attribute holds its value, which has references of its own: the attribute keeps to its name.
        val range = (element as? XmlAttribute)?.nameElement?.textRange ?: element.textRange

        // A string inside a template of this one holds its own references.
        fun ours(at: Int): Boolean =
            at >= range.startOffset &&
                (element !is KtStringTemplateExpression || file.findElementAt(at)?.parentOfType<KtStringTemplateExpression>() == element)
        val out = ArrayList<PsiReference>()
        for (read in reads) {
            val nameStart = read.end - read.name.length
            if (read.end <= range.endOffset && ours(nameStart)) out += SignalPsiReference(element, nameStart, read.name)
        }
        for (d in SignalIndex.getInstance(file.project).declarationsIn(file)) {
            if (d.end <= range.endOffset && ours(d.start)) out += SignalPsiReference(element, d.start, d.name, d.end)
        }
        return out.toTypedArray()
    }
}

/**
 * A signal read or defined at [start] in [element]'s file, written [path]: `user.name.length` reads
 * `user.name` if the project defines it, or else `user`. The reference covers the part it resolves
 * to, which is what a rename rewrites; a defining place covers the name as written.
 */
class SignalPsiReference(
    element: PsiElement,
    private val start: Int,
    private val path: String,
    private val definedEnd: Int? = null,
) : PsiPolyVariantReferenceBase<PsiElement>(element) {
    /** The signal this reference stands for, or the whole path when the project defines none of it. */
    val signal: String by lazy { if (definedEnd != null) path else resolvedName(element.containingFile, path) }

    override fun calculateDefaultRangeInElement(): TextRange {
        val base = element.textRange.startOffset
        val end = definedEnd ?: (start + signal.length)
        return TextRange(start - base, end - base)
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        SignalTarget.of(element.containingFile, signal).map { PsiElementResolveResult(it) }.toTypedArray()

    /** A read of `user.name` is a usage of `user` as well. */
    override fun isReferenceTo(element: PsiElement): Boolean =
        element is SignalTarget && (element.name == signal || signal.startsWith(element.name + "."))

    override fun getVariants(): Array<Any> = emptyArray()

    /** A defining place is no read: it is where go to declaration lands, and where find usages starts. */
    val defines: Boolean get() = definedEnd != null

    private companion object {
        fun resolvedName(
            file: PsiFile,
            path: String,
        ): String {
            val index = SignalIndex.getInstance(file.project)
            var p = path
            while (true) {
                if (index.declarationsOf(p).isNotEmpty()) return p
                if ('.' !in p) return path
                p = p.substringBeforeLast('.')
            }
        }
    }
}
