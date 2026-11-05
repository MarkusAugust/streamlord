package io.github.markusaugust.streamlord.intellij

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.util.PathUtil
import io.github.markusaugust.streamlord.core.domain.Trusted
import io.github.markusaugust.streamlord.html.Casing
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary
import kotlinx.html.TagConsumer
import java.io.File

/**
 * A module with the Kotlin standard library, Streamlord's core and DSL, and kotlinx.html: the
 * project of a Streamlord user, in which the DSL's names are Streamlord's. One object, so every
 * test class on it shares the light project.
 */
object StreamlordProject : LibrariesProject(
    "kotlin-stdlib" to jarOf(KotlinVersion::class.java),
    "streamlord-core" to jarOf(Trusted::class.java),
    "streamlord-html" to jarOf(Casing::class.java),
    "kotlinx-html" to jarOf(TagConsumer::class.java),
)

/** The Kotlin standard library and kotlinx.html, and nothing of Streamlord. */
object KotlinxHtmlProject : LibrariesProject(
    "kotlin-stdlib" to jarOf(KotlinVersion::class.java),
    "kotlinx-html" to jarOf(TagConsumer::class.java),
)

/** A Ktor user without the DSL: the standard library, streamlord-core and streamlord-ktor. */
object CoreKtorProject : LibrariesProject(
    "kotlin-stdlib" to jarOf(KotlinVersion::class.java),
    "streamlord-core" to jarOf(Trusted::class.java),
    "streamlord-ktor" to { markerJar(StreamlordLibrary.KTOR)!! },
)

/** Every module of Streamlord the plugin knows, with kotlinx.html. */
object AllModulesProject : LibrariesProject(
    *StreamlordProject.libraries.toTypedArray(),
    "streamlord-ktor" to { markerJar(StreamlordLibrary.KTOR)!! },
    "streamlord-spring" to { markerJar(StreamlordLibrary.SPRING)!! },
)

/** A light module with the jars of [libraries], found when the module is made. */
abstract class LibrariesProject(
    vararg libraries: Pair<String, () -> File>,
) : LightProjectDescriptor() {
    val libraries: List<Pair<String, () -> File>> = libraries.toList()

    override fun getModuleTypeId(): String = "JAVA_MODULE"

    override fun configureModule(
        module: Module,
        model: ModifiableRootModel,
        contentEntry: ContentEntry,
    ) {
        super.configureModule(module, model, contentEntry)
        for ((name, jar) in libraries) {
            val file = jar()
            PsiTestUtil.addLibrary(model, name, file.parent, file.name)
        }
    }
}

private fun jarOf(type: Class<*>): () -> File = { File(PathUtil.getJarPathForClass(type)) }

/** The jar or folder that holds [library]'s marker class on the test's class path, found by its class file. */
fun markerJar(library: StreamlordLibrary): File? =
    PathManager
        .getResourceRoot(StreamlordProject::class.java.classLoader, library.marker.replace('.', '/') + ".class")
        ?.let { File(it) }

/** A platform test in [StreamlordProject]. */
abstract class StreamlordTestCase : BasePlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = StreamlordProject
}

/** The Streamlord templates offered where a key is typed at the caret, as Tab finds them. */
fun streamlordTemplates(
    fixture: CodeInsightTestFixture,
    name: String,
    text: String,
): List<String> {
    fixture.configureByText(name, text)
    return streamlordTemplates(fixture)
}

/** The Streamlord templates offered at the caret of the file open in [fixture]. */
fun streamlordTemplates(fixture: CodeInsightTestFixture): List<String> =
    TemplateManagerImpl
        .listApplicableTemplates(TemplateActionContext.expanding(fixture.file, fixture.editor))
        .filter { it.groupName == "Streamlord" }
        .map { it.key }
        .sorted()
