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
            // The rewrite gives Trusted interpolate's text, a String, in which the value is escaped; no fix wraps it in Trusted.
            val fixes = found.single().fixes
            assertEquals(listOf("Mark the values with + in interpolate { }, which escapes the values"), fixes.map { it.title }, call)
            assertTrue(fixes.single().text.contains("\${+name}") && fixes.single().text.endsWith(".text"), fixes.single().text)
        }
    }

    @Test
    fun `Trusted written with its package or under an alias is core's`() {
        val qualified = "fun page(q: String) = io.github.markusaugust.streamlord.core.domain.Trusted(\"q <b>\$q</b>\")\n"
        val aliased = "import io.github.markusaugust.streamlord.core.domain.Trusted as T\nfun page(q: String) = T(\"q <b>\$q</b>\")\n"
        val spaced = qualified.replace("domain.Trusted", "domain .Trusted")
        val wrapped = qualified.replace("domain.Trusted", "domain\n    .Trusted")
        for (src in listOf(qualified, aliased, spaced, wrapped)) {
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
    fun `a value in a val of interpolate's block is told that a mark is right only where the string goes into the markup`() {
        // Whether the string goes to the markup or to a log only its uses tell, which the text cannot follow: the value is
        // warned of as before, the advice says where a mark belongs, and the fix is not preferred.
        val log =
            imports +
                "fun page(x: String) = interpolate {\n    val err = \"warn: <b>\$x</b>\"\n    log.warn(err)\n    \"<p>\${+x}</p>\"\n}\n"
        val told = issues(log).single { log.substring(it.start, it.end) == "\$x" }
        assertEquals(IssueCode.INTERPOLATE_TEMPLATE, told.code)
        assertTrue(told.message.contains("where it goes elsewhere, a log, it is no markup, and a + would be written there"), told.message)
        assertEquals(listOf(false), told.fixes.map { it.preferred })
        // A string the block writes itself, its result, is told to mark the value, and the fix is preferred as ever.
        val result = imports + "fun page(x: String) = interpolate { \"<p>\$x</p>\" }\n"
        val own = issues(result).single { result.substring(it.start, it.end) == "\$x" }
        assertTrue(!own.message.contains("a log"), own.message)
        assertEquals(listOf(true), own.fixes.map { it.preferred })
    }

    @Test
    fun `a val of the block whose value may reach the markup keeps its values judged`() {
        fun warnsOnX(body: String): Boolean {
            val src = imports + "fun wrap(s: String) = s\nfun page(x: String, xs: List<String>, c: Boolean): Html {\n$body\n}\n"
            return issues(src).any { src.substring(it.start, it.end) == "\$x" }
        }
        val shapes =
            listOf(
                // The block a val is set to.
                "    val html = interpolate { \"Hi <b>\$x</b>\" }\n    return html",
                // A val of the block that is its result, or is written into it.
                "    return interpolate {\n        val html = \"Hi <b>\$x</b>\"\n        html\n    }",
                "    return interpolate {\n        val part = \"Hi <b>\$x</b>\"\n        \"<p>\$part</p>\"\n    }",
                "    return interpolate {\n        val part = \"Hi <b>\$x</b>\"\n        \"<p>\" + part\n    }",
                "    return interpolate {\n        val part = \"Hi <b>\$x</b>\"\n        \"<p>\${part}</p>\"\n    }",
                "    return interpolate {\n        val part = if (c) \"Hi <b>\$x</b>\" else \"none\"\n        \"<p>\$part</p>\"\n    }",
                "    return interpolate {\n        val part = wrap(\"Hi <b>\$x</b>\")\n        \"<p>\$part</p>\"\n    }",
                // A val in a lambda or a local function of the block.
                "    return interpolate {\n        fun row(x: String): String {\n" +
                    "            val r = \"Hi <li>\$x</li>\"\n            return r\n        }\n        \"<ul>\${row(x)}</ul>\"\n    }",
                // A val passed to a call that is no statement of its own, but a value.
                "    return interpolate {\n        val part = \"Hi <b>\$x</b>\"\n        \"<p>\${wrap(part)}</p>\"\n    }",
            )
        for (body in shapes) assertTrue(warnsOnX(body), body)
    }

    @Test
    fun `the code a fix writes for Trusted is silent, and a mark in Trusted's text is left alone`() {
        fun valuesTold(body: String): List<String> {
            val src = imports + "fun page(name: String, url: String) = $body\n"
            return issues(src).map { src.substring(it.start, it.end) + " " + it.code.id }
        }
        // What the rewrite writes: Trusted takes interpolate's text, whose mark is that interpolate's own.
        assertEquals(emptyList(), valuesTold("Trusted(interpolate { \"Hi <i>\${+name}</i>\" }.text)"))
        assertEquals(emptyList(), valuesTold("Trusted(interpolate { \"<a href=\\\"\${+url}\\\">x</a>\" }.text)"))
        assertEquals(emptyList(), valuesTold("interpolate { \"<li>\${+Trusted(interpolate { \"Hi <i>\${+name}</i>\" }.text)}</li>\" }"))
        // Trusted written unmarked into the block's markup gives the block its text, marks and all.
        assertEquals(emptyList(), valuesTold("interpolate { \"<li>\${Trusted(\"Hi <i>\${+name}</i>\")}</li>\" }"))
        // Whether a mark in Trusted's text is read takes the code around Trusted, which the text cannot follow: left alone.
        assertEquals(emptyList(), valuesTold("interpolate { \"<li>\${+Trusted(\"Hi <i>\${+name}</i>\")}</li>\" }"))
    }

    @Test
    fun `the rewrite inside Trusted is offered but not preferred, since it escapes what the author may have built`() {
        val src = imports + "fun page(prefix: String, x: String) = Trusted(prefix + \"<b>\$x</b>\")\n"
        val fixes = issues(src).single { src.substring(it.start, it.end) == "\$x" }.fixes
        assertEquals(listOf(false), fixes.map { it.preferred })
    }
}
