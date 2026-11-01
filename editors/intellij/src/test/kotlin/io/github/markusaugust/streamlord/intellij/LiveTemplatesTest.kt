package io.github.markusaugust.streamlord.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LiveTemplatesTest : BasePlatformTestCase() {
    // Two templates of one name in a group leave only one in the IDE.
    fun `test the generated html templates have one name each`() {
        val xml = javaClass.getResource("/liveTemplates/StreamlordHtml.xml")!!.readText()
        val names = Regex("""<template name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(names.toString(), names.size, names.toSet().size)
        assertTrue(names.toString(), "data-init" in names && "data-init-get" in names)
    }

    // A value Datastar takes as text, not as an expression.
    fun `test a text value is a value, not an expression`() {
        val xml = javaClass.getResource("/liveTemplates/StreamlordHtml.xml")!!.readText()
        assertTrue(xml, xml.contains("<template name=\"data-nonce\" value=\"data-nonce=&quot;\$VALUE\$&quot;\$END\$\""))
        assertTrue(xml, xml.contains("<variable name=\"VALUE\" expression=\"\" defaultValue=\"&quot;value&quot;\""))
    }
}
