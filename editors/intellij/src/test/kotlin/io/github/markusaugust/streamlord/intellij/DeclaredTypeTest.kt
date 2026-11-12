package io.github.markusaugust.streamlord.intellij

import io.github.markusaugust.streamlord.intellij.inspections.KotlinInterpolationInspection

/**
 * Whether a value is Streamlord's markup by the type it is declared with, as the inspection judges
 * it. A value that is not markup gets the hint to write it through `interpolate`.
 */
class DeclaredTypeTest : StreamlordTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "com/acme/Html.kt",
            "package com.acme\n\nclass Html\n\nclass Markup\n\nclass Trusted(val html: String)\n",
        )
        myFixture.enableInspections(KotlinInterpolationInspection())
    }

    /** Whether the inspection asks for `interpolate` around the value `hole` in [text]. */
    private fun hinted(
        text: String,
        hole: String,
    ): Boolean {
        myFixture.configureByText("Page.kt", text)
        return myFixture.doHighlighting().any { it.text == hole && it.inspectionToolId == "KotlinInterpolationInspection" }
    }

    fun `test Streamlord's Html is markup`() {
        assertFalse(
            hinted("package p\n\nimport io.github.markusaugust.streamlord.core.domain.Html\n\nfun f(h: Html) = \"<p>\$h</p>\"\n", "\$h"),
        )
    }

    fun `test an Html imported from another package is no markup`() {
        assertTrue(hinted("package p\n\nimport com.acme.Html\n\nfun f(h: Html) = \"<p>\$h</p>\"\n", "\$h"))
    }

    fun `test a type imported as Html is no markup`() {
        assertTrue(hinted("package p\n\nimport com.acme.Markup as Html\n\nfun f(h: Html) = \"<p>\$h</p>\"\n", "\$h"))
    }

    fun `test an Html of another package written in full is no markup`() {
        assertTrue(hinted("package p\n\nfun f(h: com.acme.Html) = \"<p>\$h</p>\"\n", "\$h"))
    }

    fun `test an Html the file declares is no markup`() {
        assertTrue(hinted("package p\n\nclass Html\n\nfun f(h: Html) = \"<p>\$h</p>\"\n", "\$h"))
    }

    fun `test a joinToString of an Html imported from another package is no markup`() {
        assertTrue(
            hinted(
                "package p\n\nimport com.acme.Html\n\nfun f(items: List<Html>) = \"<ul>\${items.joinToString(\"\")}</ul>\"\n",
                "\${items.joinToString(\"\")}",
            ),
        )
    }

    fun `test a Trusted imported from another package is no markup`() {
        assertTrue(hinted("package p\n\nimport com.acme.Trusted\n\nfun f(t: Trusted) = \"<p>\$t</p>\"\n", "\$t"))
    }

    fun `test a call to a Trusted of another package is no markup`() {
        assertTrue(hinted("package p\n\nimport com.acme.Trusted\n\nfun f(x: String) = \"<p>\${Trusted(x)}</p>\"\n", "\${Trusted(x)}"))
    }

    fun `test an Html of the file's own package declared elsewhere is no markup`() {
        myFixture.addFileToProject("p/Html.kt", "package p\n\nclass Html\n")
        assertTrue(hinted("package p\n\nfun f(h: Html) = \"<p>\$h</p>\"\n", "\$h"))
    }

    fun `test an Html a star import resolves to another package is no markup`() {
        assertTrue(hinted("package p\n\nimport com.acme.*\n\nfun f(h: Html) = \"<p>\$h</p>\"\n", "\$h"))
    }

    fun `test a joinToString of an Html a star import resolves elsewhere is no markup`() {
        assertTrue(
            hinted(
                "package p\n\nimport com.acme.*\n\nfun f(items: List<Html>) = \"<ul>\${items.joinToString(\"\")}</ul>\"\n",
                "\${items.joinToString(\"\")}",
            ),
        )
    }

    fun `test an Html in backticks from another package is no markup`() {
        assertTrue(hinted("package p\n\nimport com.acme.*\n\nfun f(h: `Html`) = \"<p>\$h</p>\"\n", "\$h"))
    }

    fun `test a Trusted of no package the file declares is no markup`() {
        assertTrue(hinted("class Trusted(val s: String)\n\nfun f(t: Trusted) = \"<p>\$t</p>\"\n", "\$t"))
    }

    fun `test a Trusted literal a star import resolves elsewhere is not read in place`() {
        val text = "package p\n\nimport com.acme.*\n\nfun f(x: String) = \"<div>\${Trusted(\"<a href=\")}\$x</div>\"\n"
        assertTrue(hinted(text, "\${Trusted(\"<a href=\")}"))
    }

    fun `test a Trusted a star import resolves elsewhere is not let into a script`() {
        myFixture.configureByText(
            "Page.kt",
            "package p\n\nimport com.acme.*\nimport io.github.markusaugust.streamlord.core.domain.interpolate\n\n" +
                "fun f(x: String) = interpolate(\"<script>%s</script>\", Trusted(x))\n",
        )
        assertTrue(myFixture.doHighlighting().any { it.text == "%s" && it.description?.contains("script") == true })
    }

    fun `test Streamlord's Html under another name is markup`() {
        assertFalse(
            hinted(
                "package p\n\nimport io.github.markusaugust.streamlord.core.domain.Html as Page\n\nfun f(h: Page) = \"<p>\$h</p>\"\n",
                "\$h",
            ),
        )
    }

    fun `test Streamlord's Trusted called under another name is markup`() {
        assertFalse(
            hinted(
                "package p\n\nimport io.github.markusaugust.streamlord.core.domain.Trusted as T\n\nfun f(x: String) = \"<p>\${T(x)}</p>\"\n",
                "\${T(x)}",
            ),
        )
    }
}
