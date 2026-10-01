package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.analysis.SignalFacts
import io.github.markusaugust.streamlord.analysis.SourceLanguage
import io.github.markusaugust.streamlord.analysis.collectSignalFacts
import io.github.markusaugust.streamlord.analysis.signalDrift
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signals this service reads, against the signals the documentation's pages declare.
 *
 * It is the one check here that spans the two halves of the application. The pages are Astro and
 * the handlers are Kotlin, so a signal renamed in one and not the other compiles, deploys, and
 * then quietly hands the handler a default on every request. Nothing else in this build would
 * notice.
 */
class SignalDriftTest {
    private val kotlinSources = listOf("src/main/kotlin")
    private val markupSources = listOf("../docs/src/pages", "../docs/src/content/docs", "../docs/src/components")

    private fun corpus(): Map<String, SignalFacts> {
        val facts = LinkedHashMap<String, SignalFacts>()
        for (dir in kotlinSources) {
            File(dir).walkTopDown().filter { it.extension == "kt" }.forEach {
                facts[it.path] = collectSignalFacts(it.readText(), SourceLanguage.KOTLIN)
            }
        }
        for (dir in markupSources) {
            File(dir).walkTopDown().filter { it.extension in setOf("astro", "md", "html") }.forEach {
                facts[it.path] = collectSignalFacts(it.readText(), SourceLanguage.HTML)
            }
        }
        return facts
    }

    @Test
    fun `every signal this service reads is declared by a page`() {
        val facts = corpus()

        assertTrue(facts.size > 20, "the corpus is ${facts.size} files; the paths are wrong")
        assertEquals(
            emptyMap(),
            signalDrift(facts),
            "a signal is read here that no page declares",
        )
    }

    /** The check is only worth running if it would fail, so this proves it would. */
    @Test
    fun `a signal renamed here and not on the pages is caught`() {
        val facts = LinkedHashMap(corpus())
        val main = File("src/main/kotlin/io/github/markusaugust/streamlord/demo/Main.kt")
        facts[main.path] =
            collectSignalFacts(
                main.readText().replace("val query: String = \"\"", "val querry: String = \"\""),
                SourceLanguage.KOTLIN,
            )

        val issues = signalDrift(facts).values.flatten()

        assertEquals(1, issues.size, issues.toString())
        assertTrue(issues[0].message.contains("SearchSignals.querry"), issues[0].message)
    }
}
