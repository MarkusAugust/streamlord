package io.github.markusaugust.streamlord.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases of the VS Code extension's `markup.test.ts`. */
class MarkupTest {
    private val analyzer = Analyzer()
    private val opts = AnalyzeOptions("data-", true)
    private val raw = "\"\"\""

    private fun codes(issues: List<Issue>) = issues.map { it.code }

    private fun html(src: String) = analyzer.analyzeHtml(src, opts)

    private fun marked(
        src: String,
        issues: List<Issue>,
    ) = issues.map { src.substring(it.start, it.end) }

    private fun markup(
        src: String,
        requireIds: Boolean = false,
    ) = analyzer.validateMarkup(src, MarkupOptions(requireIds, "data-", true))

    @Test
    fun `applies an expression fix inside the attribute value`() {
        val src = "<button class=\"btn\" data-on:click=\"@GET('/x')\">go</button>"
        val fixed = "<button class=\"btn\" data-on:click=\"@get('/x')\">go</button>"
        assertEquals(fixed, html(src)[0].fixes[0].apply(src))
        val kotlin = "val x = $raw$src$raw"
        assertEquals("val x = $raw$fixed$raw", analyzer.analyzeKotlin(kotlin, opts)[0].fixes[0].apply(kotlin))
    }

    @Test
    fun `leaves ordinary data attributes alone`() {
        val names = "id test href type key row rel next icon state open kind size theme signal animated effects".split(" ")
        assertEquals(emptyList(), html("<div ${names.joinToString(" ") { "data-$it=\"x\"" }}></div>"))
    }

    @Test
    fun `still catches a swapped letter in a long name and any slip with a key or a modifier`() {
        val src =
            "<div data-indicater=\"x\" data-computer=\"1\" data-onn:click=\"1\" data-signal:x=\"1\" data-txt__once=\"1\"></div>"
        val issues = html(src)
        assertEquals(List(5) { "unknown-attribute" }, codes(issues))
        assertEquals(
            listOf("data-indicator", "data-computed", "data-on", "data-signals", "data-text"),
            issues.map { it.fixes[0].text },
        )
    }

    @Test
    fun `accepts a bare number as a duration`() {
        assertEquals(emptyList(), html($$"<input data-on:input__debounce.500=\"$a = 1\" data-on:click__delay.500=\"$a = 1\">"))
        assertEquals(listOf("modifier-args"), codes(html($$"<input data-on:input__debounce.5x=\"$a = 1\">")))
    }

    @Test
    fun `decodes character references before it reads the expression`() {
        val ok =
            $$"<div data-show=\"$a &amp;&amp; $b\" data-text=\"&quot;x&quot; + $c\" data-show=\"$a &lt; 3\" " +
                "data-text=\"&#39;a&#39; + &apos;b&apos; + &#x27;c&#x27;\"></div>"
        assertEquals(emptyList(), html(ok))
        val src = $$"<div data-text=\"&quot;x&quot; + $a-b\" data-on:click=\"$a &amp;&amp; @GET(&quot;/x&quot;)\"></div>"
        val issues = html(src)
        assertEquals(listOf($$"$a-b", "@GET"), marked(src, issues))
        assertEquals(src.replace("@GET", "@get"), issues[1].fixes[0].apply(src))
    }

    @Test
    fun `does not shift offsets after a character whose lowercase form is longer`() {
        val src = "<script>${"İ".repeat(12)}</script><div data-onn:click=\"1\"></div>"
        assertEquals(listOf("data-onn:click"), marked(src, html(src)))
    }

    @Test
    fun `does not read an attribute written in capitals as a camelCase key`() {
        assertEquals(emptyList(), html($$"<BUTTON DATA-ON:CLICK=\"$a = 1\" data-on:CLICK=\"$a = 1\">"))
        assertEquals(listOf("key-case"), codes(html($$"<button data-on:widgetLoaded=\"$a = 1\">")))
    }

    @Test
    fun `checks an expression that holds a javascript template literal`() {
        assertEquals(listOf("signal-kebab"), codes(html($$"<div data-text=\"`a ${$x-y}`\"></div>")))
        assertEquals(emptyList(), html($$"<div data-text=\"${name} +\"></div>"))
    }

    @Test
    fun `reports what datastar refuses to apply`() {
        val src = "<input data-bind:foo=\"bar\"><input data-bind><div data-text></div><button data-on:click></button>"
        assertEquals(listOf("key-and-value", "missing-key-or-value", "missing-value", "missing-value"), codes(html(src)))
        assertEquals(emptyList(), html("<input data-bind:foo><input data-bind=\"foo\"><div data-signals:x></div>"))
    }

    @Test
    fun `marks the underscores of an empty modifier`() {
        val src = $$"<button data-on:click__=\"$a = 1\"></button>"
        val issues = html(src)
        assertEquals(listOf("unknown-modifier"), codes(issues))
        assertEquals(listOf("__"), marked(src, issues))
    }

    @Test
    fun `accepts the end tags html lets the author leave out`() {
        assertEquals(emptyList(), markup("<li>a<li>b"))
        assertEquals(emptyList(), markup("<tr><td>a<td>b"))
        assertEquals(emptyList(), markup("<option>a<option>b"))
        assertEquals(emptyList(), markup("<p>one<p>two"))
        assertEquals(emptyList(), markup("<table id=\"t\"><thead><tr><th>h<tbody><tr><td>a<td>b<tr><td>c</table>", true))
        assertEquals(emptyList(), markup("<dl id=\"l\"><dt>a<dd>b<dt>c<dd>d</dl>", true))
        assertEquals(listOf("unclosed"), codes(markup("<div id=\"d\"><span>x<li>a<li>b</div>", true)))
    }

    @Test
    fun `sees each element after an omitted end tag as top-level`() {
        assertEquals(listOf("missing-id"), codes(markup("<li id=\"a\">a<li>b", true)))
    }

    @Test
    fun `keeps a tag open across a template construct inside it`() {
        val erb = $$"<div class=\"a\" <% if (x) { %> data-onn:a=\"1\" <% } %> data-show=\"$b-c\"></div>"
        assertEquals(listOf("data-onn:a", $$"$b-c"), marked(erb, html(erb)))
        val jte = $$"<div @if(a > b) data-onn:a=\"1\" @endif data-show=\"$b-c\"></div>"
        assertEquals(listOf("data-onn:a", $$"$b-c"), marked(jte, html(jte)))
        val pebble = $$"<div {% if a > b %}data-onn:a=\"1\"{% endif %} data-show=\"$b-c\"></div>"
        assertEquals(listOf("data-onn:a", $$"$b-c"), marked(pebble, html(pebble)))
    }

    @Test
    fun `skips pebble and mustache comments`() {
        val src = "{# <div data-onn:w=\"1\"> #}{{! <div data-onn:z=\"1\"> }}{{!-- <div data-onn:q=\"1\"> --}}"
        assertEquals(emptyList(), html(src))
        assertEquals(listOf("unknown-attribute"), codes(html("{#if x}<div data-onn:s=\"1\"></div>{/if}")))
    }

    @Test
    fun `reads the content of textarea and title as text`() {
        assertEquals(emptyList(), html("<textarea><div data-onn:click=\"1\"></textarea><title><div data-onn:y=\"1\"></title>"))
    }
}
