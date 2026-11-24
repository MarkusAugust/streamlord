package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** A type's brackets, read once for the file and by every reader of it, as Kotlin's grammar has them. */
class TypeBracketsTest {
    private val core = "import io.github.markusaugust.streamlord.core.domain.*\n"

    /** Each pair of a type's brackets in [src], by the text from `<` to `>`. */
    private fun pairs(src: String): List<String> {
        val types = fileFacts(src).types
        return types.indices.filter { types[it] > it }.map { src.substring(it, types[it] + 1) }
    }

    @Test
    fun `an annotation in a type's brackets is no name after a name, qualified or with a use-site target`() {
        assertEquals(listOf("<@a.B Int>"), pairs("val x: List<@a.B Int> = l"))
        assertEquals(listOf("<@field:A Int>"), pairs("val x: List<@field:A Int> = l"))
        assertEquals(
            listOf("<@org.jspecify.annotations.Nullable String>"),
            pairs("val x: List<@org.jspecify.annotations.Nullable String> = l"),
        )
    }

    @Test
    fun `a type parameter may be reified, and a property's type parameters bounded`() {
        assertEquals(listOf("<reified T>"), pairs("inline fun <reified T> f() = 1"))
        assertEquals(listOf("<T : Any>"), pairs("val <T : Any> T.x get() = 1"))
    }

    @Test
    fun `an arrow points only after a function type's brackets`() {
        assertEquals(
            emptyList(),
            pairs("fun f(a: Int, b: Int, c: Int, d: Int) = when {\n    a < b -> f()\n    c > d -> g()\n    else -> h()\n}"),
        )
        assertEquals(listOf("<(Int) -> Unit>"), pairs("val f: List<(Int) -> Unit> = l"))
    }

    @Test
    fun `an annotation in a parameter's type arguments is no part of what they hold`() {
        for (type in listOf("List<@A Html>", "List<@Suppress(\"x\") Html>", "List<out @A Html>")) {
            assertEquals(
                emptyList(),
                Analyzer().analyzeKotlin(core + "fun f(items: $type) = \"<ul>\${items.joinToString(\"\")}</ul>\""),
                type,
            )
            assertEquals(
                emptyList(),
                Analyzer().analyzeKotlin(core + "class C(val items: $type) {\n    fun f() = \"<ul>\${items.joinToString(\"\")}</ul>\"\n}"),
                type,
            )
            assertEquals(
                emptyList(),
                Analyzer().analyzeKotlin(core + "val items: $type = l\nfun f() = \"<ul>\${items.joinToString(\"\")}</ul>\""),
                type,
            )
        }
    }

    @Test
    fun `a lambda's parameter whose type has type arguments is declared`() {
        for (type in listOf("Map<String, Int>", "Map<String, () -> Unit>")) {
            val src = "val g = { f: $type, s: Int -> \"<p>\$s</p>\" }"
            assertEquals(emptyList(), Analyzer().analyzeKotlin(src).filter { it.code == IssueCode.UNESCAPED_INTERPOLATION }, type)
        }
    }

    @Test
    fun `a type parameter after one bounded by a function type is read`() {
        val src = "fun <T : (Int) -> Unit, R> f(r: R) = \"<p>\$r</p>\""
        assertEquals(true, mayBeNull(src, "r", src.indexOf("\$r")))
    }

    @Test
    fun `a function's return type and type parameters are read past their type brackets`() {
        for (type in listOf("Map<String, () -> Unit>", "List<List<List<Int>>>", "List<Int>?", "List <Int>")) {
            val src = "fun f(): $type = TODO()\n"
            assertEquals(listOf(type), fileFacts(src).functions["f"].orEmpty().map { it.type }, type)
        }
        val bounded = "fun <T : Comparable<List<T>>> g(x: T): String = \"\"\n"
        assertEquals(listOf("String"), fileFacts(bounded).functions["g"].orEmpty().map { it.type })
    }

    @Test
    fun `a property with type parameters is read past them`() {
        val src = "val <T : Comparable<T>> T.x: Int get() = 1\nval y: Int = 2\n"
        val names = fileFacts(src).names
        assertEquals(listOf(false, true), listOf("x", "y").map { names.containsKey(it) })
    }
}
