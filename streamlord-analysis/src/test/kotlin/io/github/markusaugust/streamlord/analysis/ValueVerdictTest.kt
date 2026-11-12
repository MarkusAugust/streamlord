package io.github.markusaugust.streamlord.analysis

import io.github.markusaugust.streamlord.core.domain.Position
import io.github.markusaugust.streamlord.core.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of `cases/value-verdict.json`. */
class ValueVerdictTest {
    private val cases = sharedCases("value-verdict.json")

    @Test
    fun `the verdict on a fact in each position`() {
        for (c in cases) {
            val fact = c.string("fact")?.let { ValueFact.valueOf(it.uppercase().replace('-', '_')) }
            val verdicts = c["verdicts"] as JsonObject
            assertEquals(Position.entries.map { it.name }.toSet(), verdicts.keys, c.string("name"))
            for (position in Position.entries) {
                assertEquals(verdicts.string(position.name), verdict(fact, position).name, "${c.string("name")} in ${position.name}")
            }
        }
    }
}
