package io.github.markusaugust.streamlord.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.LightProjectDescriptor
import io.github.markusaugust.streamlord.analysis.ValueFact
import io.github.markusaugust.streamlord.analysis.fact
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
                .executeOnPooledThread<Map<String, Pair<String?, ValueFact?>>> {
                    ReadAction
                        .nonBlocking<Map<String, Pair<String?, ValueFact?>>> {
                            StreamlordAnalysis
                                .getInstance(project)
                                .valueAnswers(file)
                                .entries
                                .associate { (range, answer) -> range.substring(file.text) to (answer.type to answer.fact()) }
                        }.executeSynchronously()
                }.get()
        val html = "io.github.markusaugust.streamlord.core.domain.Html"
        val trusted = "io.github.markusaugust.streamlord.core.domain.Trusted"
        val wanted =
            mapOf(
                "\$list" to ("java.util.ArrayList<$html>" to ValueFact.MARKUP),
                "\$set" to ("java.util.HashSet<$html>" to ValueFact.MARKUP),
                "\$deque" to ("kotlin.collections.ArrayDeque<$html>" to ValueFact.MARKUP),
                "\$any" to ("kotlin.collections.List<*>" to ValueFact.COLLECTION),
                "\$t" to (null to null),
                "\$ts" to ("kotlin.collections.List<*>" to ValueFact.COLLECTION),
                "\${j.name()}" to ("kotlin.String?" to ValueFact.NULLABLE_STRING),
                "\${j.rows()}" to ("kotlin.collections.MutableList<$trusted>?" to ValueFact.MARKUP),
                "\${System.getProperty(\"a\")}" to ("kotlin.String?" to ValueFact.NULLABLE_STRING),
            )
        assertEquals(wanted.entries.joinToString("\n"), answers.entries.joinToString("\n"))
    }
}
