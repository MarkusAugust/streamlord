package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** An annotation is read to its end once for the file, as Kotlin reads it, by every reader of it. */
class AnnotationEndTest {
    @Test
    fun `a file annotation may have blanks before its name and its arguments`() {
        for (header in listOf("@file: JvmName(\"X\")", "@file:JvmName (\"X\")", "@file:JvmName\t(\"X\")")) {
            val src = "$header\n\nfun f() = 1\n"
            val edit = importEdits(src, "a.b", "C").single()
            assertEquals(header.length, edit.start, header)
        }
    }

    @Test
    fun `an annotated function type's brackets are the type's, not the annotation's arguments`() {
        for (type in listOf(
            "@Composable () -> Unit",
            "@Composable (Int) -> String",
            "@Composable (() -> Unit)?",
            "@Composable (suspend () -> Unit)?",
        )) {
            val src = "fun f(content: $type, s: String) = 1\n"
            val at = src.indexOf('@')
            val facts = fileFacts(src)
            assertEquals(at + "@Composable".length, annotationEnd(src, facts.code, facts.types, at), type)
        }
        val list = "fun f(xs: List<@Foo () -> Html>) = 1\n"
        assertEquals(listOf("() -> Html"), fileFacts(list).names["xs"].orEmpty().map { it.typeArguments?.trim() })
    }

    @Test
    fun `a use-site target before a hard keyword on the next line is no annotation, and leaves the declaration alone`() {
        val src = "class A {\n    @field:\n    val x = 1\n    @get:\n    fun f(s: String) = 1\n}\n"
        val facts = fileFacts(src)
        assertEquals(listOf(true, true), listOf("x", "s").map { facts.names.containsKey(it) })
        val typed = "class A {\n    @get:\n    typealias\n}\n"
        val at = "class A {\n    ".length
        assertEquals(at, fileFacts(typed).let { annotationEnd(typed, it.code, it.types, at) })
    }

    @Test
    fun `a long literal in an annotation's arguments, a query, is read to its end`() {
        val query = "SELECT a FROM b WHERE (c = d)\n".repeat(200)
        val src = "@Query(\"\"\"$query\"\"\")\nfun f() = 1\n"
        assertEquals(true, fileFacts(src).inAnnotation(src.indexOf("\"\"\"")))
        val header = "@file:Suppress(\"\"\"$query\"\"\")\n\nfun g() = 1\n"
        assertEquals(header.indexOf("\n\nfun"), importEdits(header, "a.b", "C").single().start)
    }

    @Test
    fun `an @file target being typed ends past its colon, and one with its name on the next line is read to its end`() {
        for (next in listOf("private fun f() = 1", "typealias X = Int", "data class D(val a: Int)", "fun g() = 1")) {
            val src = "@file:\n\n$next\n"
            assertEquals(6, importEdits(src, "a.b", "C").single().start, next)
        }
        for (named in listOf("@file:\nJvmName(\"X\")\n\nfun f() = 1\n", "@file:data.Foo(\"x\")\n\nfun f() = 1\n")) {
            assertEquals(named.indexOf("\n\nfun"), importEdits(named, "a.b", "C").single().start, named)
        }
    }

    @Test
    fun `an annotation's name may begin with a hard keyword and go on in any letter`() {
        for (name in listOf("forØkt", "inÅ", "doÆ")) {
            val src = "@$name(\"x\") val a = 1\n"
            val facts = fileFacts(src)
            assertEquals(src.indexOf(" val"), annotationEnd(src, facts.code, facts.types, 0), name)
        }
    }
}
