package io.github.markusaugust.streamlord.demo

import io.github.markusaugust.streamlord.analysis.Analyzer
import io.github.markusaugust.streamlord.analysis.Issue
import io.github.markusaugust.streamlord.analysis.Severity
import io.github.markusaugust.streamlord.core.domain.DatastarEvent
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.html.dataOnClick
import io.github.markusaugust.streamlord.html.patchElements
import io.github.markusaugust.streamlord.html.set
import io.ktor.util.logging.Logger
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

/** How much the judge reads, in bytes of UTF-8. A page of markup is a few hundred; this is a paste, not a file. */
internal const val SOURCE_MAX = 1024

/** How many findings the verdict shows. The rest are counted: a paste wrong five hundred times over has had its answer by the eighth. */
internal const val FINDINGS_MAX = 8

/** How many fixes a finding offers, the preferred first. Each button carries the whole fixed text; the analysis offers up to four. */
internal const val FIXES_MAX = 2

/**
 * What the reader typed. `data-bind:source` on the field keeps it in the signal, and the section
 * around the field watches that signal with `data-on-signal-patch` and posts the store again when
 * it changes, debounced so a paste is judged once and not one keystroke at a time.
 */
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

/** One fix as its button offers it: the analysis's title for it, and the whole paste with it applied. */
public data class Fixed(
    val title: String,
    val source: String,
)

/**
 * One finding of the verdict: the issue, in the analysis's own words, and the fixes it offers.
 *
 * [fixed] holds the whole text with each fix applied, which is what the button has to put back in
 * the field. A `Fix` is a range replacement, and its text alone is the helper call without the
 * line it belongs to. At most [FIXES_MAX] of them.
 */
public data class Finding(
    val issue: Issue,
    val fixed: List<Fixed>,
)

/**
 * The verdict on one paste: which analysis read it, the first [FINDINGS_MAX] of its findings, the
 * count of all of them, so the page can say how many it is not showing, and how many of all of
 * them are errors, which sets the colour.
 */
public data class Judgement(
    val judged: Judged,
    val findings: List<Finding>,
    val total: Int,
    val errors: Int,
) {
    /** How many findings the verdict counts but does not show. */
    val more: Int get() = total - findings.size
}

/** Stateless apart from a one-entry lex cache, so one instance serves every request. */
private val analyzer = Analyzer()

/**
 * The same analysis the editors run, behind a route.
 *
 * Which analysis is a question of what the text looks like: a paste that starts with `<` is a
 * template, anything else is Kotlin.
 *
 * On [Dispatchers.Default], because the analysis is a lexer and a parser over the paste, which is
 * CPU, and the server's threads are for the connections.
 *
 * Only the first [FINDINGS_MAX] issues become findings, with [FIXES_MAX] fixes each, because every
 * fix is the whole paste over again: a 1 KiB paste that is wrong five hundred times, with four fixes
 * to each, is two megabytes of buttons for an answer that fits in eight lines.
 */
public suspend fun judge(source: String): Judgement =
    withContext(Dispatchers.Default) {
        val judged = if (source.trim().startsWith("<")) Judged.HTML else Judged.KOTLIN
        val issues =
            when (judged) {
                Judged.HTML -> analyzer.analyzeHtml(source)
                Judged.KOTLIN -> analyzer.analyzeKotlin(source)
            }
        val findings =
            issues.take(FINDINGS_MAX).map { issue ->
                Finding(issue, issue.fixes.take(FIXES_MAX).map { Fixed(it.title, it.apply(source)) })
            }
        Judgement(judged, findings, total = issues.size, errors = issues.count { it.severity == Severity.ERROR })
    }

/**
 * The verdict as markup for `#verdict`, or the one verdict that needs no analysis.
 *
 * The limit is checked here and not in the plugin, so that a paste over it gets a verdict on the
 * page rather than a 413 the client turns into a fetch error nobody reads. The plugin's own limit
 * is four times this: a body that declares a length above it is refused before it is read, and
 * one that declares none is read up to the limit and no further.
 *
 * An analysis that throws is noted in [log] by the exception's class alone, so a broken analysis
 * shows up without the paste, which is nobody's business and which the message would quote.
 */
public suspend fun judgeEvents(
    signals: JudgeSignals,
    log: Logger,
): List<DatastarEvent> {
    if (signals.source.encodeToByteArray().size > SOURCE_MAX) return listOf(verdictPatch { overLimit() })
    if (signals.source.isBlank()) return listOf(verdictPatch { nothingYet() })

    // A paste the analysis cannot get through is a verdict too, and one that quotes none of it:
    // an exception would reach StatusPages with the text in its message. The rendering is inside
    // the same try, because the fix buttons go through the DSL's expression guard.
    val event =
        try {
            val judgement = judge(signals.source)
            verdictPatch { verdict(judgement) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("The judge could not read a paste: {}", e::class.simpleName)
            verdictPatch { unreadable() }
        }
    return listOf(event)
}

/** The verdict when the judge read nothing: the body was over the plugin's limit, and the paste is gone. */
public fun tooLargeEvents(): List<DatastarEvent> = listOf(verdictPatch { overLimit() })

private fun verdictPatch(block: TagConsumer<*>.() -> Unit): DatastarEvent =
    patchElements(selector = "#verdict", mode = ElementPatchMode.INNER) { block() }

private fun TagConsumer<*>.overLimit() {
    div("fs-alert judge__verdict") {
        attributes["data-color"] = "warning"
        p("fs-alert__title") { +"Too long" }
        p { +"The judge reads at most 1 KiB of UTF-8." }
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
 * button and the keyboard all reach the judge the same way. On the page, `data-signals` has to
 * stand before `data-on-signal-patch` on the section: in the other order the client sees the
 * signal arrive and posts once on load.
 */
public fun TagConsumer<*>.verdict(judgement: Judgement) {
    if (judgement.total == 0) {
        div("fs-alert judge__verdict") {
            attributes["data-color"] = "success"
            p("fs-alert__title") { +"Clean" }
            p { +"The browser will see exactly this." }
        }
        p("fs-paragraph judge__how") {
            attributes["data-size"] = "small"
            +judgement.judged.says
        }
        return
    }

    div("fs-alert judge__verdict") {
        attributes["data-color"] = if (judgement.errors > 0) "danger" else "warning"
        p("fs-alert__title") {
            +when (judgement.total) {
                1 -> "One thing the browser would not do"
                else -> "${judgement.total} things the browser would not do"
            }
        }
        ul("fs-list judge__findings") {
            attributes["data-variant"] = "plain"
            judgement.findings.forEach { finding ->
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
                    finding.fixed.forEach { fixed ->
                        button(classes = "fs-button judge__fix") {
                            type = ButtonType.button
                            attributes["data-variant"] = "secondary"
                            attributes["data-size"] = "small"
                            // The fixed text as a JavaScript literal, escaped by the helper, so it
                            // is a string to the client and not an expression the server signed.
                            dataOnClick(set("source", fixed.source))
                            +fixed.title
                        }
                    }
                }
            }
            if (judgement.more > 0) {
                li("judge__finding judge__more") { +"and ${judgement.more} more" }
            }
        }
    }
    p("fs-paragraph judge__how") {
        attributes["data-size"] = "small"
        +judgement.judged.says
    }
}
