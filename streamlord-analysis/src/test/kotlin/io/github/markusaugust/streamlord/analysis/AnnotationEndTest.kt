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
        for (type in listOf("@Composable () -> Unit", "@Composable (Int) -> String")) {
            val src = "fun f(content: $type, s: String) = 1\n"
            val at = src.indexOf('@')
            val facts = fileFacts(src)
            assertEquals(at + "@Composable".length, annotationEnd(src, facts.code, facts.types, at), type)
        }
        val list = "fun f(xs: List<@Foo () -> Html>) = 1\n"
        assertEquals(listOf("() -> Html"), fileFacts(list).names["xs"].orEmpty().map { it.typeArguments?.trim() })
    }

    @Test
    fun `a use-site target with nothing after it on its line leaves the next line's declaration alone`() {
        val src = "class A {\n    @field:\n    val x = 1\n    @get:\n    fun f(s: String) = 1\n}\n"
        val facts = fileFacts(src)
        assertEquals(listOf(true, true), listOf("x", "s").map { facts.names.containsKey(it) })
    }

    @Test
    fun `a long literal in an annotation's arguments, a query, is read to its end`() {
        val query = "SELECT a FROM b WHERE (c = d)\n".repeat(200)
        val src = "@Query(\"\"\"$query\"\"\")\nfun f() = 1\n"
        assertEquals(true, fileFacts(src).inAnnotation(src.indexOf("\"\"\"")))
        val header = "@file:Suppress(\"\"\"$query\"\"\")\n\nfun g() = 1\n"
        assertEquals(header.indexOf("\n\nfun"), importEdits(header, "a.b", "C").single().start)
    }
}
