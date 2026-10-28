package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonObject
import io.github.markusaugust.streamlord.core.port.driven.BuiltInSignalsCodec
import io.github.markusaugust.streamlord.core.port.driven.toJsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ToJsonValueTest {
    data class Form(
        val email: String = "",
    )

    @Test
    fun `the value comes back as the codec wrote it`() {
        val json = BuiltInSignalsCodec.toJsonValue(mapOf("first-name" to "", "big" to java.math.BigInteger("12345678901234567890")))
        assertIs<JsonObject>(json)
        assertEquals(setOf("first-name", "big"), json.keys)
        assertEquals(JsonNumber("12345678901234567890"), json["big"])
    }

    @Test
    fun `the built-in codec still refuses a data class`() {
        assertFailsWith<SignalsCodecException> { BuiltInSignalsCodec.toJsonValue(Form()) }
    }
}
