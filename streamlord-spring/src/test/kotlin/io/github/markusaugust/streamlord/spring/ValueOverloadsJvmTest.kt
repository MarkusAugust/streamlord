package io.github.markusaugust.streamlord.spring

import io.github.markusaugust.streamlord.core.json.JsonBoolean
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.json.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The calls here that take any value the codec encodes, inline functions with a reified type, which
 * the API dump leaves out, are the ones the catalog's `events` flag `value` (EventCallsJvmTest
 * in streamlord-analysis holds the rest).
 */
class ValueOverloadsJvmTest {
    @Test
    fun `the calls that take a value the codec encodes are the ones the catalog says`() {
        val catalog = File("../catalog").listFiles()!!.single { it.name.startsWith("datastar-") }
        val events = JsonParser.parseObject(catalog.readText()).obj("kotlinCallSites")!!.obj("events")!!
        val methods = Class.forName("io.github.markusaugust.streamlord.spring.ResponsesKt").methods.filter { it.name in events }
        // The value is the first parameter after a receiver, if any, that is no String.
        val taking =
            methods
                .filter { m -> m.parameterTypes.dropWhile { !it.name.startsWith("java.lang") }.firstOrNull() == Any::class.java }
                .map { it.name }
                .toSet()
        val flagged = methods.map { it.name }.filter { (events[it] as JsonObject)["value"] == JsonBoolean.TRUE }.toSet()
        assertEquals(flagged, taking)
    }
}
