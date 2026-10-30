package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.client.clientRewrite
import io.github.markusaugust.streamlord.core.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scanner, the string reader and the collectors against `twins/analyze.json`, which
 * `analyze.test.ts` reads as well; what they report as issues is in the shared corpus.
 */
class AnalyzerTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)

    private fun literal(s: KotlinString?): Map<String, Any?>? =
        s?.let {
            mapOf(
                "start" to it.start.toLong(),
                "end" to it.end.toLong(),
                "raw" to it.raw,
                "dollars" to it.dollars.toLong(),
                "contentStart" to it.contentStart.toLong(),
                "contentEnd" to it.contentEnd.toLong(),
                "text" to it.text,
                "map" to it.map.map(Int::toLong),
                "interpolations" to
                    it.interpolations.map { i ->
                        mapOf(
                            "start" to i.start.toLong(),
                            "end" to i.end.toLong(),
                            "kind" to i.kind.name.lowercase(),
                            "text" to i.text,
                            "decodedStart" to i.decodedStart.toLong(),
                        )
                    },
                "unterminated" to it.unterminated,
            )
        }

    private fun issues(case: JsonObject): List<Issue> {
        val prefix = case.string("prefix")!!
        val source = case.string("source")!!
        return when (case.string("language")) {
            "markup" -> analyzer.validateMarkup(source, MarkupOptions(false, prefix, true))
            "kotlin" -> analyzer.analyzeKotlin(source, AnalyzeOptions(prefix, true))
            else -> analyzer.analyzeHtml(source, AnalyzeOptions(prefix, true))
        }
    }

    /** What each kind of case expects, in the fields `twins/analyze.json` describes; `analyze.test.ts` projects the same. */
    private fun project(case: JsonObject): Map<String, Any?> {
        val source = case.string("source") ?: ""
        return when (val kind = case.string("kind")) {
            "kotlin-string" -> {
                mapOf("string" to literal(readKotlinStringAt(source, case.int("at")!!)))
            }

            "call-sites" -> {
                val sites = findCallSites(source, case.array("names")!!.strings().toSet())
                mapOf(
                    "sites" to
                        sites.map { s ->
                            mapOf(
                                "name" to s.name,
                                "trailingLambda" to s.trailingLambda,
                                "args" to s.args.map { mapOf("named" to it.named, "text" to it.text, "string" to it.string?.text) },
                            )
                        },
                )
            }

            "kotlin-strings" -> {
                mapOf("texts" to findKotlinStrings(source).map { it.text })
            }

            "html-string-at" -> {
                val needles = case.array("at")!!.objects().map { it.string("needle")!! }
                mapOf("at" to needles.map { mapOf("needle" to it, "raw" to analyzer.htmlStringAt(source, source.indexOf(it) + 1)?.raw) })
            }

            "signals" -> {
                val language = SourceLanguage.valueOf(case.string("language")!!.uppercase())
                mapOf("signals" to collectSignals(source, language).sorted())
            }

            "selectors" -> {
                val s = collectSelectors(source)
                mapOf("ids" to s.ids.sorted(), "classes" to s.classes.sorted())
            }

            "fixture" -> {
                val text = File("../editors/vscode/test/fixtures/${case.string("file")}").readText()
                mapOf("codes" to analyzer.analyzeHtml(text, opts).map { it.code })
            }

            "issues" -> {
                mapOf(
                    "issues" to
                        issues(case).map {
                            mapOf(
                                "code" to it.code,
                                "start" to it.start.toLong(),
                                "end" to it.end.toLong(),
                                "severity" to it.severity.name.lowercase(),
                            )
                        },
                )
            }

            else -> {
                error("Unknown case kind $kind")
            }
        }
    }

    @Test
    fun `holds every twin case of the analysis`() {
        val cases = twinCases("analyze.json")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val actual = project(case)
            assertEquals(actual.keys.associateWith { case[it]?.toKotlin() }, actual, case.string("name"))
        }
    }

    @Test
    fun `documents attributes and actions`() {
        val on = analyzer.catalog.attributesByName["on"]!!
        val doc = attributeDoc(on)
        assertContains(doc, "**data-on**")
        assertContains(doc, "__debounce.500ms")
        assertContains(doc, "**Key casing:**")
        assertContains(actionDoc(analyzer.catalog.actionsByName["post"]!!), "@post(uri, options?)")
        assertContains(markdownToHtml("**a** `b` _c_"), "<b>a</b> <code>b</code> <i>c</i>")
        assertEquals("<p><code>__once</code>, <code>__exit</code> <i>Pro</i></p>", markdownToHtml("`__once`, `__exit` _Pro_"))
        assertFalse(
            analyzer.catalog.attributesByName["persist"]!!
                .let { attributeDoc(it) }
                .contains("**Key casing:**")
                .not(),
        )
        assertNotNull(analyzer.catalog.parseAttributeName("data-on:click__debounce.500ms", "data-"))
    }
}

class TokensTest {
    @Test
    fun `tokenizes datastar expressions`() {
        val text = "\$count++; @post('/x', {headers: {'X-A': \$token}}); el.value; /a\\/b/g.test(evt.key) && \${'\$'}open"
        val tokens = tokenizeExpression(text).map { text.substring(it.start, it.end) to it.kind }
        assertEquals(
            listOf(
                "\$count" to TokenKind.SIGNAL,
                "@post" to TokenKind.ACTION_BACKEND,
                "'/x'" to TokenKind.STRING,
                "headers" to TokenKind.OPTION_KEY,
                "'X-A'" to TokenKind.STRING,
                "\$token" to TokenKind.SIGNAL,
                "el" to TokenKind.SCOPE_VARIABLE,
                "/a\\/b/g" to TokenKind.REGEX,
                "evt" to TokenKind.SCOPE_VARIABLE,
                "\${'\$'}" to TokenKind.KOTLIN_DOLLAR_ESCAPE,
                "open" to TokenKind.SIGNAL,
            ),
            tokens,
        )
        assertEquals(
            listOf(
                "\$user" to TokenKind.SIGNAL,
                ".name.first" to TokenKind.SIGNAL_PATH,
                "500ms" to TokenKind.DURATION,
                "3" to TokenKind.NUMBER,
            ),
            tokenizeExpression("\$user.name.first + 500ms + 3").let { t ->
                t.map {
                    "\$user.name.first + 500ms + 3".substring(it.start, it.end) to
                        it.kind
                }
            },
        )
        assertEquals(listOf(TokenKind.NUMBER, TokenKind.KEYWORD), tokenizeExpression("x = a ? 1 : typeof b").map { it.kind })
    }

    @Test
    fun `reads a signal as far as the client's pattern does`() {
        fun tokens(text: String) = tokenizeExpression(text).map { text.substring(it.start, it.end) to it.kind }
        assertEquals(listOf("\$123" to TokenKind.SIGNAL), tokens("\$123"))
        assertEquals(listOf("\$foo-bar" to TokenKind.SIGNAL), tokens("\$foo-bar"))
        assertEquals(listOf("\$foo" to TokenKind.SIGNAL, ".0.name" to TokenKind.SIGNAL_PATH), tokens("\$foo.0.name"))
        assertEquals(listOf("\$foo-bar" to TokenKind.SIGNAL, ".baz" to TokenKind.SIGNAL_PATH), tokens("\$foo-bar.baz"))
        assertEquals(listOf("\$a-b-1" to TokenKind.SIGNAL), tokens("\$a-b-1"))
        // The trailing dot is outside the client's match, so outside the token.
        assertEquals(listOf("\$foo" to TokenKind.SIGNAL), tokens("\$foo."))
        assertEquals(listOf("\${'\$'}" to TokenKind.KOTLIN_DOLLAR_ESCAPE, "123" to TokenKind.SIGNAL), tokens("\${'\$'}123"))
        // The signal and its path together cover what the client rewrites.
        for (text in listOf("\$123 + \$foo-bar.baz-qux.0 - \$a-b-1; \$foo. + \$x-", "\$a.b-c.d + \$_1-2_")) {
            val covered =
                tokenizeExpression(text).fold(ArrayList<IntRange>()) { acc, t ->
                    if (t.kind == TokenKind.SIGNAL_PATH) acc[acc.lastIndex] = acc.last().first until t.end else acc += t.start until t.end
                    acc
                }
            assertEquals(clientRewrite(text).rewrites.map { it.start until it.end }, covered, text)
        }
    }

    @Test
    fun `splits attribute names into parts`() {
        val name = "data-on:click__debounce.500ms.leading__once"
        val parts = tokenizeAttributeName(name, "data-")!!.map { name.substring(it.start, it.end) to it.part }
        assertEquals(
            listOf(
                "data-" to NamePart.PREFIX,
                "on" to NamePart.PLUGIN,
                ":" to NamePart.KEY_SEPARATOR,
                "click" to NamePart.KEY,
                "__" to NamePart.MODIFIER_SIGIL,
                "debounce" to NamePart.MODIFIER,
                "." to NamePart.MODIFIER_ARG_DOT,
                "500ms" to NamePart.MODIFIER_ARG,
                "." to NamePart.MODIFIER_ARG_DOT,
                "leading" to NamePart.MODIFIER_ARG,
                "__" to NamePart.MODIFIER_SIGIL,
                "once" to NamePart.MODIFIER,
            ),
            parts,
        )
        assertNull(tokenizeAttributeName("class", "data-"))
        assertEquals(listOf(NamePart.PREFIX, NamePart.PLUGIN), tokenizeAttributeName("data-text", "data-")!!.map { it.part })
    }
}
