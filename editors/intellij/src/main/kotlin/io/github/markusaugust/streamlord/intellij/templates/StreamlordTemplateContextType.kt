package io.github.markusaugust.streamlord.intellij.templates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import io.github.markusaugust.streamlord.intellij.analysis.ProjectLibraries
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary.CORE
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary.HTML
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary.KTOR
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordLibrary.SPRING
import org.jetbrains.kotlin.idea.liveTemplates.KotlinTemplateContextType

/**
 * Where a Kotlin context of the Kotlin plugin is, in a module that has the Streamlord modules a
 * template writes calls of. A template's contexts are any one of them, so each pairing of a Kotlin
 * context with the modules is a context of its own. While the IDE indexes the modules are not
 * known, and every template is offered, as before.
 */
abstract class StreamlordTemplateContextType(
    presentableName: String,
    // An instance of the Kotlin plugin's own: the platform's registry of contexts is internal API.
    private val kotlin: KotlinTemplateContextType,
    private val needs: List<StreamlordLibrary>,
) : TemplateContextType(presentableName) {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        if (!kotlin.isInContext(templateActionContext)) return false
        val file = templateActionContext.file
        return needs.all { ProjectLibraries.known(file, it) != false }
    }
}

class StreamlordCoreStatementContextType :
    StreamlordTemplateContextType("Statement, with streamlord-core", KotlinTemplateContextType.Statement(), listOf(CORE))

class StreamlordCoreExpressionContextType :
    StreamlordTemplateContextType("Expression, with streamlord-core", KotlinTemplateContextType.Expression(), listOf(CORE))

class StreamlordHtmlStatementContextType :
    StreamlordTemplateContextType("Statement, with streamlord-html", KotlinTemplateContextType.Statement(), listOf(HTML))

class StreamlordHtmlExpressionContextType :
    StreamlordTemplateContextType("Expression, with streamlord-html", KotlinTemplateContextType.Expression(), listOf(HTML))

class StreamlordKtorStatementContextType :
    StreamlordTemplateContextType("Statement, with streamlord-ktor", KotlinTemplateContextType.Statement(), listOf(KTOR))

class StreamlordKtorExpressionContextType :
    StreamlordTemplateContextType("Expression, with streamlord-ktor", KotlinTemplateContextType.Expression(), listOf(KTOR))

class StreamlordKtorHtmlStatementContextType :
    StreamlordTemplateContextType(
        "Statement, with streamlord-ktor and streamlord-html",
        KotlinTemplateContextType.Statement(),
        listOf(KTOR, HTML),
    )

class StreamlordKtorHtmlExpressionContextType :
    StreamlordTemplateContextType(
        "Expression, with streamlord-ktor and streamlord-html",
        KotlinTemplateContextType.Expression(),
        listOf(KTOR, HTML),
    )

class StreamlordSpringClassContextType :
    StreamlordTemplateContextType("Class, with streamlord-spring", KotlinTemplateContextType.Class(), listOf(SPRING))

class StreamlordSpringTopLevelContextType :
    StreamlordTemplateContextType("Top level, with streamlord-spring", KotlinTemplateContextType.TopLevel(), listOf(SPRING))
