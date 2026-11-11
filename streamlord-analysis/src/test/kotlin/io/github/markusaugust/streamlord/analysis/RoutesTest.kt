package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of `twins/routes.json`, which the VS Code extension's `routes.test.ts` reads too. */
class RoutesTest {
    // As routes.test.ts shows a route: framework, method and path, then what its handler reads.
    private fun shown(r: Route) =
        (
            listOf("${r.framework.name.lowercase()} ${r.method} ${r.path}") +
                r.query.map { "?${it.name}${if (it.required) "" else "?"}" } +
                r.headers.map { "H ${it.name}${if (it.required) "" else "?"}" }
        ).joinToString(" ")

    @Test
    fun `route detection`() =
        eachCase(twinCases("routes.json"), { it.string("name")!! }) { c ->
            val name = c.string("name")!!
            val source = c.array("source")!!.strings().joinToString("\n")
            assertEquals(c.array("expected")!!.strings(), findRoutes(source).map(::shown), name)
        }
}
