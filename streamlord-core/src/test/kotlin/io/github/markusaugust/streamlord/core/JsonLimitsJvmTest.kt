package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.JsonWriter
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonLimitsJvmTest {
    @Test
    fun `a number at the limit is read exactly`() {
        val digits = "9".repeat(JsonParser.MAX_NUMBER_LENGTH)

        assertEquals(BigDecimal(digits), JsonParser.parseObject("""{"n":$digits}""").decimal("n"))
    }

    @Test
    fun `a decimal with an enormous exponent is written as it is, not as two billion zeros`() {
        assertEquals("""{"n":1E+2000000000}""", JsonWriter.write(mapOf("n" to BigDecimal("1E+2000000000"))))
        assertEquals("""{"n":1.10}""", JsonWriter.write(mapOf("n" to BigDecimal("1.10"))))
    }
}
