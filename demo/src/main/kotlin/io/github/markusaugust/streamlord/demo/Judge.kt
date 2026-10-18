package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.analysis.Analyzer
import io.github.markusaugust.streamlord.analysis.Issue
import io.github.markusaugust.streamlord.analysis.Severity
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.html.dataOnClick
import io.github.markusaugust.streamlord.html.js
import io.github.markusaugust.streamlord.html.patchElements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.html.ButtonType
import kotlinx.html.TagConsumer
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.Serializable

/** How much the judge reads. A page of markup is a few hundred characters; this is a paste, not a file. */
internal const val SOURCE_MAX = 1024

/** What the reader typed. `data-bind:source` on the field is the whole of the client side. */
@Serializable
public data class JudgeSignals(
    val source: String = "",
)

/** Which of the two analyses read the text, named in the verdict so the reader knows what was judged. */
public enum class Judged(
    val says: String,
) {
    HTML("Read as HTML: attributes and expressions."),
    KOTLIN("Read as Kotlin: the strings and the DSL calls in it."),
}

/**
 * The verdict on one paste: the issues, in the analysis's own words, and the fix each one offers.
 *
 * [fixed] holds the whole text with that fix applied, which is what the button has to put back in
 * the field. A `Fix` is a range replacement, and its text alone is the helper call without the
 * line it belongs to.
 */
public data class Finding(
    val issue: Issue,
    val fixed: List<Pair<String, String>>,
)

/**
 * The same analysis the editors run, behind a route.
 *
 * Which analysis is a question of what the text looks like: a paste that starts with `<` is a
 * template, anything else is Kotlin. The analyzer is stateless apart from a one-entry lex cache,
 * so one instance serves every request.
 *
 * On [Dispatchers.Default], because the analysis is a lexer and a parser over the paste, which is
 * CPU, and the server's threads are for the connections.
 */
private val analyzer = Analyzer()

public suspend fun judge(source: String): Pair<Judged, List<Finding>> =
    withContext(Dispatchers.Default) {
        val judged = if (source.trim().startsWith("<")) Judged.HTML else Judged.KOTLIN
        val issues =
            when (judged) {
                Judged.HTML -> analyzer.analyzeHtml(source)
                Judged.KOTLIN -> analyzer.analyzeKotlin(source)
            }
        judged to issues.map { issue -> Finding(issue, issue.fixes.map { it.title to it.apply(source) }) }
    }

/**
 * The verdict as markup for `#verdict`, or the one verdict that needs no analysis.
 *
 * The limit is checked here and not in the plugin, so that a paste over it gets a verdict on the
 * page rather than a 413 the client turns into a fetch error nobody reads. The plugin's own limit
 * is four times this and refuses the body before any of it is read.
 */
public suspend fun judgeEvents(signals: JudgeSignals): List<DatastarEvent> {
    if (signals.source.length > SOURCE_MAX) return listOf(verdictPatch { overLimit() })
    if (signals.source.isBlank()) return listOf(verdictPatch { nothingYet() })

    // A paste the analysis cannot get through is a verdict too, and one that quotes none of it:
    // an exception would reach StatusPages with the text in its message.
    val (judged, findings) =
        try {
            judge(signals.source)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return listOf(verdictPatch { unreadable() })
        } catch (_: StackOverflowError) {
            return listOf(verdictPatch { unreadable() })
        }
    return listOf(verdictPatch { verdict(judged, findings) })
}

/** The verdict when the judge read nothing: the body was over the plugin's limit, and the paste is gone. */
public fun tooLargeEvents(): List<DatastarEvent> = listOf(verdictPatch { overLimit() })

private fun verdictPatch(block: TagConsumer<*>.() -> Unit): DatastarEvent =
    patchElements(selector = "#verdict", mode = ElementPatchMode.INNER) { block() }

private fun TagConsumer<*>.overLimit() {
    div("fs-alert judge__verdict") {
        attributes["data-color"] = "warning"
        p("fs-alert__title") { +"Too long" }
        p { +"The judge reads at most 1 KiB." }
    }
}

/** An empty field is not clean markup, so it is not told it is. */
private fun TagConsumer<*>.nothingYet() {
    p("fs-paragraph judge__how") {
        attributes["data-size"] = "small"
        +"Paste markup or Kotlin and the judge reads it."
    }
}

private fun TagConsumer<*>.unreadable() {
    div("fs-alert judge__verdict") {
        attributes["data-color"] = "warning"
        p("fs-alert__title") { +"Unread" }
        p { +"The judge could not get through this. It is neither markup nor Kotlin it recognises." }
    }
}

/**
 * The findings, in Fristil's classes, which the page has and the server has never seen.
 *
 * Each message is the analysis's own, unchanged, because the message is what the editors show
 * and the page's claim is that this is that check. The button under a finding puts the fixed
 * text back into `$source`; the page watches that signal and asks again, so the field, the
 * button and the keyboard all reach the judge the same way.
 */
public fun TagConsumer<*>.verdict(
    judged: Judged,
    findings: List<Finding>,
) {
    if (findings.isEmpty()) {
        div("fs-alert judge__verdict") {
            attributes["data-color"] = "success"
            p("fs-alert__title") { +"Clean" }
            p { +"The browser will see exactly this." }
        }
        p("fs-paragraph judge__how") {
            attributes["data-size"] = "small"
            +judged.says
        }
        return
    }

    val errors = findings.count { it.issue.severity == Severity.ERROR }
    div("fs-alert judge__verdict") {
        attributes["data-color"] = if (errors > 0) "danger" else "warning"
        p("fs-alert__title") {
            +when (findings.size) {
                1 -> "One thing the browser would not do"
                else -> "${findings.size} things the browser would not do"
            }
        }
        ul("fs-list judge__findings") {
            attributes["data-variant"] = "plain"
            findings.forEach { finding ->
                li("judge__finding") {
                    span("judge__severity") {
                        +finding.issue.severity.name
                            .lowercase()
                    }
                    +" "
                    +finding.issue.message
                    finding.issue.code?.let { code ->
                        +" "
                        code("judge__code") { +"[$code]" }
                    }
                    finding.fixed.forEach { (title, fixed) ->
                        button(classes = "fs-button judge__fix") {
                            type = ButtonType.button
                            attributes["data-variant"] = "secondary"
                            attributes["data-size"] = "small"
                            // The fixed text as a JavaScript literal: escaped by js(), so it is a
                            // string to the client and not an expression the server signed.
                            dataOnClick("\$source = ${js(fixed)}")
                            +title
                        }
                    }
                }
            }
        }
    }
    p("fs-paragraph judge__how") {
        attributes["data-size"] = "small"
        +judged.says
    }
}
