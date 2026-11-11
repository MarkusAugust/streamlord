package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The places a file names a signal, as go to definition, find references and rename read them. */
class SignalSitesTest {
    private val html = """<div data-signals:user.name="''" data-text="${'$'}user.name.length + ${'$'}other" data-on:click="${'$'}count = 1"></div>"""
    private val declarations = signalDeclarations(html, SourceLanguage.HTML)
    private val reads = Analyzer().signalReferencesInHtml(html)
    private val sites = signalSites(reads, declarations, setOf("user", "user.name", "count"))

    private fun shown(site: SignalSite) = "${html.substring(site.start, site.end)}=${site.signal}${if (site.declaration != null) " defined" else ""}"

    @Test
    fun `each part of a read the workspace defines is a site, and a defining place is one alone`() {
        assertEquals(
            listOf("user=user", "name=user.name", "user.name=user.name defined", "count=count defined"),
            sites.map(::shown),
        )
    }

    @Test
    fun `a site is found from its start to just after its end`() {
        val name = sites.first { it.signal == "user.name" && it.declaration == null }
        assertEquals(name, siteAt(sites, name.start))
        assertEquals(name, siteAt(sites, name.end))
        assertNull(siteAt(sites, html.indexOf("other") + 1))
    }

    @Test
    fun `a site names its signal, and a defining place the signals above it`() {
        val defined = sites.first { it.declaration != null && it.signal == "user.name" }
        assertEquals(listOf(true, false, true), listOf(defined.names("user.name"), defined.names("user"), defined.names("user", under = true)))
        assertEquals(false, sites.first { it.signal == "user.name" && it.declaration == null }.names("user", under = true))
    }

    @Test
    fun `a rename is refused where it would leave reads stale or the name is Kotlin's`() {
        assertNull(sites.first().renameRefusal())
        assertEquals(
            "A rename does not find the reads of 'my-name', whose hyphen no \$name reaches.",
            SignalSite(0, 7, "my-name").renameRefusal(),
        )
        assertEquals(
            "'query' is a property of a signals class: rename it with Kotlin's rename.",
            SignalSite(0, 5, "query", SignalDeclaration(0, 5, "query", SignalDeclaration.Kind.PROPERTY)).renameRefusal(),
        )
    }

    @Test
    fun `a rename writes each site as its place wants`() {
        val written = sites.map { it.renameText(html, "user", "person") }
        assertEquals(listOf("person", "name", "person.name", "count"), written)
        val json = """<div data-signals="{user: {name: ''}}"></div>"""
        val keys = signalSites(emptyList(), signalDeclarations(json, SourceLanguage.HTML), emptySet())
        assertEquals(listOf("person", "name"), keys.map { it.renameText(json, "user", "person") })
    }
}
