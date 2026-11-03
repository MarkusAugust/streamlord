package io.github.markusaugust.streamlord.intellij.navigation

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import io.github.markusaugust.streamlord.intellij.index.SignalIndex
import org.jetbrains.kotlin.psi.KtFile
import javax.swing.Icon

/**
 * The place where a signal is defined, as a target for go to declaration and find usages. A signal
 * is no element of any language's tree: it is a key in an attribute, a pair in a DSL call or a
 * property of a signals class, so the target stands beside the file's tree at the name's offsets.
 */
class SignalTarget(
    private val file: PsiFile,
    private val range: TextRange,
    private val signal: String,
) : FakePsiElement() {
    override fun getName(): String = signal

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getTextRange(): TextRange = range

    override fun getTextOffset(): Int = range.startOffset

    override fun getNavigationElement(): PsiElement = this

    override fun isValid(): Boolean = file.isValid

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun navigate(requestFocus: Boolean) {
        val vf = file.virtualFile ?: return
        OpenFileDescriptor(project, vf, range.startOffset).navigate(requestFocus)
    }

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Variable

    /**
     * No scope of its own, so that the platform's word search, which reads every file holding the
     * name again and those the index leaves out with them, finds nothing to search:
     * [SignalReferencesSearcher] answers from the index, in the scope the search was asked for.
     */
    override fun getUseScope(): SearchScope = GlobalSearchScope.EMPTY_SCOPE

    override fun getPresentation(): ItemPresentation =
        object : ItemPresentation {
            override fun getPresentableText(): String = "\$$signal"

            override fun getLocationString(): String {
                val line =
                    file.viewProvider.document
                        ?.getLineNumber(range.startOffset)
                        ?.plus(1)
                return file.name + (line?.let { ":$it" } ?: "")
            }

            override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.Variable
        }

    override fun equals(other: Any?): Boolean =
        other is SignalTarget && other.file == file && other.range == range && other.signal == signal

    override fun hashCode(): Int = (file.hashCode() * 31 + range.hashCode()) * 31 + signal.hashCode()

    override fun toString(): String = "SignalTarget($signal at ${file.name}$range)"

    companion object {
        /** Every place the project defines [name]. */
        fun of(
            file: PsiFile,
            name: String,
        ): List<SignalTarget> {
            val manager = PsiManager.getInstance(file.project)
            return SignalIndex.getInstance(file.project).declarationsOf(name).mapNotNull { place ->
                val target = manager.findFile(place.file) ?: return@mapNotNull null
                // A call to someone else's `set("count", 1)` defines no signal.
                if (target is KtFile && !SignalSites.byStreamlord(target, place.start)) return@mapNotNull null
                SignalTarget(target, TextRange(place.start, place.end), name)
            }
        }
    }
}
