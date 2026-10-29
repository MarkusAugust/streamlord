package io.github.markusaugust.streamlord.core

import io.github.markusaugust.streamlord.core.domain.AttributeKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The key rules the DSL throws with and the analysis reports; the corpus holds the editor's twin to them. */
class AttributeKeysTest {
    @Test
    fun `kebab writes one hyphen per capital and keeps a custom property's two`() {
        assertEquals(
            listOf("foo-bar", "my-u-r-l", "url-base", "--brand-color"),
            listOf("fooBar", "myURL", "UrlBase", "--brandColor").map(AttributeKeys::kebab),
        )
    }

    @Test
    fun `a key is refused where the client would cut it elsewhere`() {
        assertEquals("contains __, where Datastar starts reading modifiers", AttributeKeys.modifierStartIn("a__b"))
        assertNull(AttributeKeys.modifierStartIn("a_b"))
        assertEquals(
            "ends in _, which runs into the __ of the modifiers after it (b___case.pascal), so Datastar's client would read the key as b",
            AttributeKeys.runsIntoModifiers("b_", "__case.pascal"),
        )
        assertNull(AttributeKeys.runsIntoModifiers("b_", ""))
        assertNull(AttributeKeys.runsIntoModifiers("b", "__once"))
    }

    @Test
    fun `an attribute with a capital is spelled as HTML may have it`() {
        assertEquals(listOf("view-box", "viewbox"), AttributeKeys.spellings("viewBox"))
        assertEquals(listOf("aria-label"), AttributeKeys.spellings("aria-Label"))
        assertEquals("{viewBox: ...}", AttributeKeys.objectForm("viewBox"))
        assertEquals("{'aria-Label': ...}", AttributeKeys.objectForm("aria-Label"))
        assertNull(AttributeKeys.capitalIn("aria-label", "dataAttr"))
    }
}
