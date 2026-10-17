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
}
