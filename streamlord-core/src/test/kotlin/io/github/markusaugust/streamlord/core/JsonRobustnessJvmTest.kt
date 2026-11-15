package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.json.JsonNumber
import io.github.markusaugust.streamlord.core.json.JsonParser
import io.github.markusaugust.streamlord.core.json.decimal
import io.github.markusaugust.streamlord.core.json.toBigDecimal
import io.github.markusaugust.streamlord.core.json.toBigDecimalOrNull
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JsonRobustnessJvmTest {
    @Test
    fun `pathological numbers do not throw from the decimal accessors`() {
        val obj = JsonParser.parseObject("""{"a":1e2147483648,"d":123456789012345678901234567890}""")
        obj.decimal("a")
        assertEquals(BigDecimal("123456789012345678901234567890"), obj.decimal("d"))
        assertNull(JsonNumber("1e99999999999").toBigDecimalOrNull())
        assertFailsWith<ArithmeticException> { JsonNumber("1e99999999999").toBigDecimal() }
    }
}
