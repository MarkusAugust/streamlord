package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a literal handed to `Trusted`, or written in the block of `interpolate`, is to the markup, and what is said of its values. */
class TrustedLiteralTest {
    private val analyzer = Analyzer()
    private val imports = "import io.github.markusaugust.streamlord.core.domain.*\n"

    private fun issues(src: String) =
        analyzer.analyzeKotlin(Source(src, SourceLanguage.KOTLIN, modules = Modules.known(core = true, htmlDsl = true)))

    /** Whether the literal that starts with [text] is read as HTML: coloured, injected and judged as markup. */
    private fun html(
        src: String,
        text: String,
    ): Boolean = isHtmlString(src, lexKotlin(src).strings.single { src.startsWith(text, it.start) })

    @Test
    fun `a value in the literal Trusted vouches for is advised into interpolate, and no fix wraps it in Trusted again`() {
        for (call in listOf("Trusted(\"Hi <i>\$name</i>\")", "interpolate(\"<li>%s</li>\", Trusted(\"Hi <i>\$name</i>\"))")) {
            val src = imports + "fun page(name: String) = $call\n"
            val found = issues(src).filter { src.substring(it.start, it.end) == "\$name" }
            assertEquals(1, found.size, call)
            assertEquals(
                "Kotlin writes \$name into the markup Trusted(...) vouches for, as it is: Trusted escapes none of its text, \$name " +
                    "included. If \$name can hold text from outside, build this markup with interpolate { } and mark \$name with +, " +
                    "which escapes it.",
                found.single().message,
                call,
            )
            assertEquals(emptyList(), found.single().fixes.map { it.title }, call)
        }
    }

    @Test
    fun `Trusted written with its package or under an alias is core's`() {
        val qualified = "fun page(q: String) = io.github.markusaugust.streamlord.core.domain.Trusted(\"q <b>\$q</b>\")\n"
        val aliased = "import io.github.markusaugust.streamlord.core.domain.Trusted as T\nfun page(q: String) = T(\"q <b>\$q</b>\")\n"
        for (src in listOf(qualified, aliased)) {
            assertTrue(html(src, "\"q <b>"), src)
            assertEquals(
                listOf("\$q"),
                issues(src)
                    .filter {
                        it.code == IssueCode.UNESCAPED_INTERPOLATION
                    }.map { src.substring(it.start, it.end) },
                src,
            )
            assertTrue(
                issues(src).single { it.code == IssueCode.UNESCAPED_INTERPOLATION }.message.contains("Trusted(...) vouches for"),
                src,
            )
        }
        // A value that is a Trusted written with its package is markup the author vouched for, as one under its name is.
        val value =
            imports + "fun page(t: String) = interpolate { \"<p>\${+io.github.markusaugust.streamlord.core.domain.Trusted(t)}</p>\" }\n"
        assertEquals(
            issues(value.replace("io.github.markusaugust.streamlord.core.domain.Trusted", "Trusted")).map {
                it.code
            },
            issues(value).map { it.code },
        )
    }

    @Test
    fun `a literal a val of interpolate's block is set to is no markup of the block, unless it marks a value`() {
        val log =
            imports +
                "fun page(x: String) = interpolate {\n    val err = \"warn: <b>\$x</b>\"\n    log.warn(err)\n    \"<p>\${+x}</p>\"\n}\n"
        assertEquals(false, html(log, "\"warn"))
        assertEquals(emptyList(), issues(log).map { log.substring(it.start, it.end) + " " + it.code.id })
        val comparison = imports + "fun page(x: String) = interpolate {\n    val cmp = \"\\\$a<b\"\n    \"<p>\${+x}</p>\"\n}\n"
        assertEquals(false, html(comparison, "\"\\\$a<b"))
        // One whose value goes into the block's markup has its values judged there, and the mark is the advice.
        val part = imports + "fun page(x: String) = interpolate {\n    val part = \"<b>\$x</b>\"\n    \"<p>\$part</p>\"\n}\n"
        assertEquals(listOf("\$x interpolate-template"), issues(part).map { part.substring(it.start, it.end) + " " + it.code.id })
        // One that marks a value is the block's markup, wherever it stands.
        val row = imports + "fun page(x: String) = interpolate {\n    val row = \"<li>\${+x}</li>\"\n    \"<ul>\$row</ul>\"\n}\n"
        assertEquals(true, html(row, "\"<li>"))
        assertEquals(true, html(row, "\"<ul>"))
    }
}
