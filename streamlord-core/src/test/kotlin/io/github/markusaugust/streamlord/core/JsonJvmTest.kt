package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonWriter
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonJvmTest {
    @Test
    fun `writes a decimal with the digits it has`() {
        assertEquals("""{"dec":1.10}""", JsonWriter.write(mapOf("dec" to BigDecimal("1.10"))))
    }
}
