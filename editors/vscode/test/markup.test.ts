import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeHtml, analyzeKotlin } from "../src/analyze.ts";
import type { Fix, Issue } from "../src/expression.ts";
import { validateMarkup } from "../src/markup.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };
const codes = (issues: { code?: string }[]) => issues.map((i) => i.code);
const apply = (src: string, fix: Fix) => src.slice(0, fix.start) + fix.text + src.slice(fix.end);
const marked = (src: string, issues: Issue[]) => issues.map((i) => src.slice(i.start, i.end));
const markup = (src: string, requireIds = false) => validateMarkup(src, { requireIds, prefix: "data-", checkAttributes: true });

describe("attributes", () => {
  it("applies an expression fix inside the attribute value", () => {
    const html = `<button class="btn" data-on:click="@GET('/x')">go</button>`;
    assert.equal(apply(html, analyzeHtml(html, opts)[0]!.fixes![0]!), `<button class="btn" data-on:click="@get('/x')">go</button>`);
    const kotlin = `val x = """${html}"""`;
    assert.equal(apply(kotlin, analyzeKotlin(kotlin, opts)[0]!.fixes![0]!), `val x = """<button class="btn" data-on:click="@get('/x')">go</button>"""`);
  });

  it("leaves ordinary data attributes alone", () => {
    const names = ["id", "test", "href", "type", "key", "row", "rel", "next", "icon", "state", "open", "kind", "size", "theme", "signal", "animated", "effects"];
    assert.deepEqual(analyzeHtml(`<div ${names.map((n) => `data-${n}="x"`).join(" ")}></div>`, opts), []);
  });

  it("still catches a swapped letter in a long name, and any slip with a key or a modifier", () => {
    const src = '<div data-indicater="x" data-computer="1" data-onn:click="1" data-signal:x="1" data-txt__once="1"></div>';
    const issues = analyzeHtml(src, opts);
    assert.deepEqual(codes(issues), Array(5).fill("unknown-attribute"));
    assert.deepEqual(issues.map((i) => i.fixes![0]!.text), ["data-indicator", "data-computed", "data-on", "data-signals", "data-text"]);
  });

  it("accepts a bare number as a duration", () => {
    assert.deepEqual(analyzeHtml('<input data-on:input__debounce.500="$a = 1" data-on:click__delay.500="$a = 1">', opts), []);
    assert.deepEqual(codes(analyzeHtml('<input data-on:input__debounce.5x="$a = 1">', opts)), ["modifier-args"]);
  });

  it("decodes character references before it reads the expression", () => {
    const ok = `<div data-show="$a &amp;&amp; $b" data-text="&quot;x&quot; + $c" data-show="$a &lt; 3" data-text="&#39;a&#39; + &apos;b&apos; + &#x27;c&#x27;"></div>`;
    assert.deepEqual(analyzeHtml(ok, opts), []);
    const src = '<div data-text="&quot;x&quot; + $a-b" data-on:click="$a &amp;&amp; @GET(&quot;/x&quot;)"></div>';
    const issues = analyzeHtml(src, opts);
    assert.deepEqual(marked(src, issues), ["$a-b", "@GET"]);
    assert.equal(apply(src, issues[1]!.fixes![0]!), src.replace("@GET", "@get"));
  });

  it("does not shift offsets after a character whose lowercase form is longer", () => {
    const src = `<script>${"İ".repeat(12)}</script><div data-onn:click="1"></div>`;
    assert.deepEqual(marked(src, analyzeHtml(src, opts)), ["data-onn:click"]);
  });

  it("does not read an attribute written in capitals as a camelCase key", () => {
    assert.deepEqual(analyzeHtml('<BUTTON DATA-ON:CLICK="$a = 1" data-on:CLICK="$a = 1">', opts), []);
    assert.deepEqual(codes(analyzeHtml('<button data-on:widgetLoaded="$a = 1">', opts)), ["key-case"]);
  });

  it("checks an expression that holds a javascript template literal", () => {
    assert.deepEqual(codes(analyzeHtml('<div data-text="`a ${$x-y}`"></div>', opts)), ["signal-kebab"]);
    assert.deepEqual(analyzeHtml('<div data-text="${name} +"></div>', opts), []);
  });

  it("reports what datastar refuses to apply", () => {
    const src = '<input data-bind:foo="bar"><input data-bind><div data-text></div><button data-on:click></button>';
    assert.deepEqual(codes(analyzeHtml(src, opts)), ["key-and-value", "missing-key-or-value", "missing-value", "missing-value"]);
    assert.deepEqual(analyzeHtml('<input data-bind:foo><input data-bind="foo"><div data-signals:x></div>', opts), []);
  });

  it("marks the underscores of an empty modifier", () => {
    const src = '<button data-on:click__="$a = 1"></button>';
    const issues = analyzeHtml(src, opts);
    assert.deepEqual(codes(issues), ["unknown-modifier"]);
    assert.deepEqual(marked(src, issues), ["__"]);
  });
});

describe("markup structure", () => {
  it("accepts the end tags html lets the author leave out", () => {
    assert.deepEqual(markup("<li>a<li>b"), []);
    assert.deepEqual(markup("<tr><td>a<td>b"), []);
    assert.deepEqual(markup("<option>a<option>b"), []);
    assert.deepEqual(markup("<p>one<p>two"), []);
    assert.deepEqual(markup('<table id="t"><thead><tr><th>h<tbody><tr><td>a<td>b<tr><td>c</table>', true), []);
    assert.deepEqual(markup('<dl id="l"><dt>a<dd>b<dt>c<dd>d</dl>', true), []);
    assert.deepEqual(codes(markup('<div id="d"><span>x<li>a<li>b</div>', true)), ["unclosed"]);
  });

  it("sees each element after an omitted end tag as top-level", () => {
    assert.deepEqual(codes(markup('<li id="a">a<li>b', true)), ["missing-id"]);
  });

  it("keeps a tag open across a template construct inside it", () => {
    const erb = '<div class="a" <% if (x) { %> data-onn:a="1" <% } %> data-show="$b-c"></div>';
    assert.deepEqual(marked(erb, analyzeHtml(erb, opts)), ["data-onn:a", "$b-c"]);
    const jte = '<div @if(a > b) data-onn:a="1" @endif data-show="$b-c"></div>';
    assert.deepEqual(marked(jte, analyzeHtml(jte, opts)), ["data-onn:a", "$b-c"]);
    const pebble = '<div {% if a > b %}data-onn:a="1"{% endif %} data-show="$b-c"></div>';
    assert.deepEqual(marked(pebble, analyzeHtml(pebble, opts)), ["data-onn:a", "$b-c"]);
  });

  it("skips pebble and mustache comments", () => {
    assert.deepEqual(analyzeHtml('{# <div data-onn:w="1"> #}{{! <div data-onn:z="1"> }}{{!-- <div data-onn:q="1"> --}}', opts), []);
    assert.deepEqual(codes(analyzeHtml('{#if x}<div data-onn:s="1"></div>{/if}', opts)), ["unknown-attribute"]);
  });

  it("reads the content of textarea and title as text", () => {
    assert.deepEqual(analyzeHtml('<textarea><div data-onn:click="1"></textarea><title><div data-onn:y="1"></title>', opts), []);
  });
});
