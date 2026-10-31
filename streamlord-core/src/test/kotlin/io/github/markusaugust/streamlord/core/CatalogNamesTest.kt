package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.catalog.CatalogNames
import io.github.markusaugust.streamlord.core.domain.ElementsGuard
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.protocol.DatastarAttributes
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The names generated from the catalog, held to the file as core's own JSON parser reads it. */
class CatalogNamesTest {
    private val catalog: JsonObject = JsonParser.parseObject(File("../catalog/datastar-${DatastarProtocol.VERSION}.json").readText())

    private fun names(kind: String?): List<String> =
        catalog
            .array("attributes")!!
            .objects()
            .filter { kind == null || (it.string("valueKind") ?: "none") == kind }
            .map { it.string("name")!! }

    @Test
    fun `the generated names are the catalog's, in its order`() {
        assertEquals(DatastarProtocol.VERSION, CatalogNames.VERSION)
        assertEquals(catalog.string("version"), CatalogNames.VERSION)
        assertEquals(catalog.string("prefix"), CatalogNames.PREFIX)
        assertEquals(catalog.string("aliasedPrefix"), CatalogNames.ALIASED_PREFIX)
        assertEquals(names(null), CatalogNames.attributes.toList())
        assertEquals(names("expression"), CatalogNames.expressionAttributes.toList())
        assertEquals(names("filter"), CatalogNames.filterAttributes.toList())
        assertEquals(names("signal"), CatalogNames.signalAttributes.toList())
    }

    @Test
    fun `the guard and the prefix read them`() {
        assertEquals(names("expression").toSet(), ElementsGuard.expressionAttributes)
        assertEquals((names("expression") + names("filter")).toSet(), ElementsGuard.evaluatedAttributes)
        assertEquals(names(null).toSet(), ElementsGuard.attributes)
        // Longest first: the guard takes the first prefix a name starts with.
        assertEquals(listOf("data-star-", "data-"), ElementsGuard.defaultPrefixes)
        assertEquals("data-", DatastarAttributes.prefix)
    }
}
