package io.github.markusaugust.streamlord.html.pro

import io.github.markusaugust.streamlord.core.domain.ElementNamespace
import io.github.markusaugust.streamlord.core.domain.ElementPatchMode
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.protocol.DatastarProtocol
import io.github.markusaugust.streamlord.html.DatastarAttributes
import io.github.markusaugust.streamlord.html.FetchEventType
import io.github.markusaugust.streamlord.html.FetchOptions
import io.github.markusaugust.streamlord.html.IntersectModifiers
import io.github.markusaugust.streamlord.html.OnModifiers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Binds `catalog/datastar-<version>.json` to the DSL. The catalog feeds the editor tooling; if the
 * DSL and the catalog ever disagree, this test is the wall they break against.
 */
class CatalogTest {

    private val catalog: JsonObject = JsonParser.parseObject(File("../catalog/datastar-${DatastarProtocol.VERSION}.json").readText())

    /** JVM names with inline-class mangling (`name-hash`) and synthetic suffixes (`name$default`) removed. */
    private fun jvmName(name: String): String = name.substringBefore('-').substringBefore('$')

    private fun methods(className: String): Set<String> =
        Class.forName(className).methods.map { jvmName(it.name) }.toSet()

    private val freeAttributeFns = methods("io.github.markusaugust.streamlord.html.AttributesKt")
    private val proAttributeFns = methods("io.github.markusaugust.streamlord.html.pro.ProAttributesKt")
    private val freeActionFns = methods("io.github.markusaugust.streamlord.html.ActionsKt")
    private val proActionFns = methods("io.github.markusaugust.streamlord.html.pro.ProActionsKt")
    private val expressionFns = methods("io.github.markusaugust.streamlord.html.ExpressionsKt")

    @Test
    fun `catalog version and prefixes match the code`() {
        assertEquals(DatastarProtocol.VERSION, catalog.string("version"))
        assertEquals(DatastarAttributes.prefix, catalog.string("prefix"))
    }

    @Test
    fun `every catalog attribute has its DSL function in the right module`() {
        for (attr in catalog.array("attributes")!!.objects()) {
            val pro = attr.boolean("pro") ?: false
            val fns = attr.array("kotlin")!!.strings()
            assertTrue(fns.isNotEmpty(), "attribute ${attr.string("name")} lists no kotlin function")
            for (fn in fns) {
                val where = if (pro) proAttributeFns else freeAttributeFns
                assertTrue(fn in where, "attribute ${attr.string("name")}: function $fn missing from ${if (pro) "pro" else "free"} module")
                if (pro) assertTrue(fn !in freeAttributeFns, "pro attribute function $fn must not exist in the free module")
            }
        }
    }

    @Test
    fun `every catalog action has its DSL function in the right module`() {
        for (action in catalog.array("actions")!!.objects()) {
            val pro = action.boolean("pro") ?: false
            val fn = action.string("kotlin")!!
            assertTrue(fn in (if (pro) proActionFns else freeActionFns), "action ${action.string("name")}: $fn missing")
        }
    }

    @Test
    fun `on and intersect modifiers are all settable on their builders`() {
        fun properties(klass: Class<*>) = klass.methods.filter { it.name.startsWith("set") }.map { jvmName(it.name).removePrefix("set").lowercase() }.toSet()
        val onProps = properties(OnModifiers::class.java)
        val intersectProps = properties(IntersectModifiers::class.java)
        val groups = catalog.obj("modifierGroups")!!
        fun expand(mods: List<JsonObject>): List<String> = mods.flatMap { m ->
            m.string("\$ref")?.let { ref -> groups.array(ref)!!.objects().map { it.string("name")!! } } ?: listOf(m.string("name")!!)
        }
        val attrs = catalog.array("attributes")!!.objects().associateBy { it.string("name")!! }
        for (name in expand(attrs.getValue("on").array("modifiers")!!.objects())) {
            assertTrue(name in onProps, "OnModifiers lacks a property for __$name")
        }
        for (name in expand(attrs.getValue("on-intersect").array("modifiers")!!.objects())) {
            assertTrue(name in intersectProps, "IntersectModifiers lacks a property for __$name")
        }
    }

    @Test
    fun `fetch options, events, modes and namespaces match`() {
        val optionProps = FetchOptions::class.java.methods.filter { it.name.startsWith("set") }.map { jvmName(it.name).removePrefix("set").replaceFirstChar { c -> c.lowercase() } }.toSet()
        for (opt in catalog.array("fetchOptions")!!.objects()) {
            assertTrue(opt.string("name") in optionProps, "FetchOptions lacks ${opt.string("name")}")
        }
        val events = FetchEventType::class.java.fields.filter { it.type == String::class.java }.map { it.get(null) as String }.toSet()
        assertEquals(events, catalog.array("fetchEvents")!!.strings().toSet())
        val sse = catalog.obj("sse")!!
        assertEquals(ElementPatchMode.entries.map { it.wire }, sse.array("patchModes")!!.strings())
        assertEquals(ElementNamespace.entries.map { it.wire }, sse.array("namespaces")!!.strings())
        assertEquals(listOf(DatastarProtocol.Events.PATCH_ELEMENTS, DatastarProtocol.Events.PATCH_SIGNALS), sse.array("events")!!.strings())
    }

    @Test
    fun `every kotlin call site the editor validates exists`() {
        val sites = catalog.obj("kotlinCallSites")!!
        val known = freeAttributeFns + proAttributeFns + freeActionFns + proActionFns + expressionFns +
            methods("io.github.markusaugust.streamlord.html.ElementsKt") +
            methods("io.github.markusaugust.streamlord.core.port.driving.DatastarStream") +
            setOf("PatchElements", "ElementsResponse", "ExecuteScript", "respondElements", "respondScript", "datastarElements", "datastarScript")
        for (group in listOf("expression", "html", "script", "selector")) {
            for (fn in sites.obj(group)!!.keys) {
                assertTrue(fn in known, "call site $fn in group $group does not exist in the SDK")
            }
        }
    }
}
