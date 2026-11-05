package io.github.markusaugust.streamlord.intellij.analysis

import com.intellij.java.library.JavaLibraryUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

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
 * sources too. Kept per file until the project's libraries change or the IDE starts or stops
 * indexing. Needs a read lock.
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
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                StreamlordLibrary.entries.map { find(file, it) }.toTypedArray(),
                ProjectRootManager.getInstance(project),
                DumbService.getInstance(project).modificationTracker,
            )
        }[library.ordinal]
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
