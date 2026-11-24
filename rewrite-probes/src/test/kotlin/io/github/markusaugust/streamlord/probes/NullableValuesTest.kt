package io.github.markusaugust.streamlord.probes

import kotlin.test.Test
import kotlin.test.assertEquals

/** What nullable-values.json says of each value against the type the Kotlin compiler gives it. */
class NullableValuesTest {
    @Test
    fun `each value may be null where the compiler types it so`() {
        val wrong = NULLABLE_VALUES.filter { it.said != it.typed }.map { "${it.value}: ${it.typed}" }
        assertEquals(emptyList(), wrong, "the compiler types these otherwise than the table says")
    }
}
