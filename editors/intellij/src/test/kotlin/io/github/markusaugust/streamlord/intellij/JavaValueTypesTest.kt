package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.LightProjectDescriptor
import io.github.markusaugust.streamlord.intellij.analysis.StreamlordAnalysis
import org.jetbrains.kotlin.psi.KtFile

/** What the plugin answers of values whose types are Java's, a star and a type parameter, in a project with a JDK. */
class JavaValueTypesTest : StreamlordTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = StreamlordJdkProject

    fun `test Java's collections, a Java value, a star and a type parameter are written as the analysis reads them`() {
        myFixture.addFileToProject(
            "types/Java.java",
            "package types;\nimport io.github.markusaugust.streamlord.core.domain.Trusted;\n" +
                "public interface Java { String name(); java.util.List<Trusted> rows(); }",
        )
        val file =
            myFixture.configureByText(
                "Page.kt",
                "import io.github.markusaugust.streamlord.core.domain.Html\n" +
                    "fun <T> f(list: java.util.ArrayList<Html>, set: HashSet<Html>, deque: ArrayDeque<Html>, any: List<*>, " +
                    "t: T, ts: List<T>, j: types.Java) =\n" +
                    "    \"<p>\$list \$set \$deque \$any \$t \$ts \${j.name()} \${j.rows()} \${System.getProperty(\"a\")}</p>\"",
            ) as KtFile
        val answers =
            ApplicationManager
                .getApplication()
                .executeOnPooledThread<Map<String, String?>> {
                    ReadAction
                        .nonBlocking<Map<String, String?>> {
                            StreamlordAnalysis
                                .getInstance(project)
                                .valueAnswers(file)
                                .entries
                                .associate { (range, answer) -> range.substring(file.text) to answer.type }
                        }.executeSynchronously()
                }.get()
        val html = "io.github.markusaugust.streamlord.core.domain.Html"
        val trusted = "io.github.markusaugust.streamlord.core.domain.Trusted"
        val wanted =
            mapOf(
                "\$list" to "java.util.ArrayList<$html>",
                "\$set" to "java.util.HashSet<$html>",
                "\$deque" to "kotlin.collections.ArrayDeque<$html>",
                "\$any" to "kotlin.collections.List<*>",
                "\$t" to null,
                "\$ts" to "kotlin.collections.List<*>",
                "\${j.name()}" to "kotlin.String?",
                "\${j.rows()}" to "kotlin.collections.MutableList<$trusted>?",
                "\${System.getProperty(\"a\")}" to "kotlin.String?",
            )
        assertEquals(wanted.entries.joinToString("\n"), answers.entries.joinToString("\n"))
    }
}
