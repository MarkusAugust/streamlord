package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.java.library.JavaLibraryUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** A module of the Streamlord SDK, known by a public class it has had since 0.10.0. */
enum class StreamlordLibrary(
    val marker: String,
) {
    HTML("io.github.markusaugust.streamlord.html.JsLiteral"),
    CORE("io.github.markusaugust.streamlord.core.domain.Trusted"),
    KTOR("io.github.markusaugust.streamlord.ktor.StreamlordPluginConfig"),
    SPRING("io.github.markusaugust.streamlord.spring.ElementPatchModeConverter"),
}

/**
 * Which of Streamlord's modules the code at an element can use, by the platform's own check, which
 * finds a jar and a source module alike. It counts a module's test-scoped libraries for its main
 * sources too. The platform keeps the answer per module until the libraries change, so the file's
 * module is looked up each time: a file moved to another module is judged by its new one at once.
 * Needs a read lock.
 */
object ProjectLibraries {
    /** Whether [library] is there for [element], or null while the IDE indexes, when the platform cannot tell. */
    fun known(
        element: PsiElement,
        library: StreamlordLibrary,
    ): Boolean? {
        val project = element.project
        // An injected fragment is a file of its own, outside every module; its host's module is the one.
        val file = InjectedLanguageManager.getInstance(project).getTopLevelFile(element) ?: element.containingFile ?: return null
        return find(file, library)
    }

    private fun find(
        file: PsiFile,
        library: StreamlordLibrary,
    ): Boolean? {
        val project = file.project
        if (DumbService.isDumb(project)) return null
        val module = ModuleUtilCore.findModuleForPsiElement(file)
        // A scratch file or one in memory belongs to no module: what the project has is the best guess.
        val found =
            if (module != null) {
                JavaLibraryUtil.hasLibraryClass(module, library.marker)
            } else {
                JavaLibraryUtil.hasLibraryClass(project, library.marker)
            }
        // The platform answers false once indexing has begun, which says nothing.
        return if (!found && DumbService.isDumb(project)) null else found
    }
}
