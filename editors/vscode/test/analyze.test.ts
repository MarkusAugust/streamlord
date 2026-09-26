import { describe, it } from "node:test";
import assert from "node:assert/strict";

const expect = (actual: unknown) => ({
  toEqual: (expected: unknown) => assert.deepEqual(actual, expected),
  toBe: (expected: unknown) => assert.equal(actual, expected),
  toBeNull: () => assert.equal(actual, null),
  toContain: (needle: string) => assert.ok(typeof actual === "string" && actual.includes(needle), `expected ${JSON.stringify(actual)} to contain ${JSON.stringify(needle)}`),
  toBeGreaterThan: (n: number) => assert.ok((actual as number) > n, `expected ${actual} > ${n}`),
});
import { analyzeHtml, analyzeKotlin } from "../src/analyze.ts";
import { readKotlinStringAt } from "../src/kotlinStrings.ts";
import { findCallSites } from "../src/scanner.ts";
import { validateExpression } from "../src/expression.ts";
import { validateMarkup } from "../src/markup.ts";
import { collectSignals } from "../src/signals.ts";
import { decodeDatastar, mergePatch, SseParser } from "../src/sse.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };
const codes = (issues: { code?: string }[]) => issues.map((i) => i.code);

describe("kotlin strings", () => {
  it("decodes escapes and templates with an offset map", () => {
    const src = 'x("a\\"b\\n${\'$\'}c$d${e}f")';
    const s = readKotlinStringAt(src, 2)!;
    expect(s.text).toBe('a"b\n$c__kt____kt__f');
    expect(s.interpolations.map((i) => [i.kind, i.text])).toEqual([["simple", "d"], ["braced", "e"]]);
    expect(src.slice(s.map[0], s.map[0]! + 1)).toBe("a");
    expect(src.slice(s.map[1], s.map[1]! + 2)).toBe('\\"');
    expect(s.end).toBe(src.length - 1);
  });

  it("reads raw strings and stops at the last quote of a run", () => {
    const src = 'f("""<div id="a">"</div>"""")';
    const s = readKotlinStringAt(src, 2)!;
    expect(s.raw).toBe(true);
    expect(s.text).toBe('<div id="a">"</div>"');
    expect(src.slice(s.end)).toBe(")");
  });
});

describe("scanner", () => {
  it("splits positional, named, string and lambda arguments", () => {
    const src = `stream.patchElements("<li>x</li>", selector = "#list", mode = ElementPatchMode.APPEND) { once = true }
      fun patchElements(x: String) = 1
      dataOn("click", post("/x") + "y")`;
    const sites = findCallSites(src, new Set(["patchElements", "dataOn", "post"]));
    expect(sites.map((s) => s.name)).toEqual(["patchElements", "dataOn", "post"]);
    const pe = sites[0]!;
    expect(pe.args.map((a) => [a.named, a.string?.text ?? a.text])).toEqual([[null, "<li>x</li>"], ["selector", "#list"], ["mode", "ElementPatchMode.APPEND"]]);
    expect(pe.trailingLambda).toBe(true);
    expect(sites[1]!.args[1]!.string).toBeNull();
  });
});

describe("expressions", () => {
  it("accepts datastar syntax", () => {
    expect(validateExpression("$count++; @post('/x', {headers: {'X-A': '1'}})")).toEqual([]);
    expect(validateExpression("evt.key === 'Escape' && ($open = false)")).toEqual([]);
    expect(validateExpression("@peek(() => $a.b)")).toEqual([]);
  });
  it("reports syntax errors with positions and unknown actions", () => {
    const bad = validateExpression("@post('/x'");
    expect(bad[0]?.severity).toBe("error");
    expect(bad[0]?.start).toBe(10);
    expect(codes(validateExpression("@Post('/x')"))).toEqual(["unknown-action"]);
    expect(validateExpression("@Post('/x')")[0]?.message).toContain("Did you mean @post");
    expect(codes(validateExpression("@clipboard('x')"))).toEqual(["pro-action"]);
    expect(codes(validateExpression("  "))).toEqual(["empty-expression"]);
  });
});

describe("markup", () => {
  it("requires ids on top-level elements without a selector", () => {
    expect(codes(validateMarkup('<div id="a">x</div><span>y</span>', { requireIds: true, prefix: "data-", checkAttributes: true }))).toEqual(["missing-id"]);
    expect(validateMarkup("<span>y</span>", { requireIds: false, prefix: "data-", checkAttributes: true })).toEqual([]);
  });
  it("finds unclosed and stray tags and top-level text", () => {
    expect(codes(validateMarkup('<div id="a"><p>x</div>', { requireIds: true, prefix: "data-", checkAttributes: true }))).toEqual(["unclosed"]);
    expect(codes(validateMarkup("</div>", { requireIds: false, prefix: "data-", checkAttributes: true }))).toEqual(["stray-close"]);
    expect(codes(validateMarkup("Hello <b>x</b>", { requireIds: false, prefix: "data-", checkAttributes: true }))).toEqual(["top-level-text"]);
    expect(validateMarkup('<input id="i"><br><script>if (a < b) {}</script>', { requireIds: false, prefix: "data-", checkAttributes: true })).toEqual([]);
  });
  it("validates data-* attributes and modifiers", () => {
    const html = `<div id="x" data-on:click__debounce.500ms.leading__prevent="@post('/a')" data-on-intersect__threshold.150="x()"
      data-on:click__debunce="1" data-on="x" data-text:x="1" data-signal:foo="1" data-bind="$a + 1" data-persist data-init__delay="1"></div>`;
    const c = codes(validateMarkup(html, { requireIds: false, prefix: "data-", checkAttributes: true }));
    expect(c).toEqual(["modifier-args", "unknown-modifier", "missing-key", "unexpected-key", "unknown-attribute", "signal-name-expected", "pro-attribute", "modifier-args"]);
    const messages = validateMarkup(html, { requireIds: false, prefix: "data-", checkAttributes: true }).map((i) => i.message);
    expect(messages.find((m) => m.includes("data-signal."))).toContain("Did you mean data-signals");
    expect(messages.find((m) => m.includes("__debunce"))).toContain("Did you mean __debounce");
  });
  it("validates expressions inside attribute values but tolerates template syntax", () => {
    expect(codes(validateMarkup('<div id="a" data-text="$x +"></div>', { requireIds: false, prefix: "data-", checkAttributes: true }))).toEqual(["expression-syntax"]);
    expect(validateMarkup('<div id="a" data-text="{{ name }}" data-show="{% if x %}1{% endif %}"></div>', { requireIds: false, prefix: "data-", checkAttributes: true })).toEqual([]);
  });
  it("honours the aliased prefix", () => {
    expect(codes(validateMarkup('<div data-star-on:click="x()" data-on:click="y("></div>', { requireIds: false, prefix: "data-star-", checkAttributes: true }))).toEqual([]);
    expect(codes(validateMarkup('<div data-star-on:click="x()"></div>', { requireIds: false, prefix: "data-", checkAttributes: true }))).toEqual(["prefix-mismatch"]);
  });
});

describe("analyzeKotlin", () => {
  it("flags kotlin interpolation of signals and maps syntax errors to source", () => {
    const src = `div {
  dataOnClick("$count++")
  dataText("\${'$'}count +")
  dataOn("keydown", "evt.key === 'x' && (\\$open = false)") { once = true }
}`;
    const issues = analyzeKotlin(src, opts);
    expect(codes(issues)).toEqual(["kotlin-interpolation", "expression-syntax"]);
    expect(src.slice(issues[0]!.start, issues[0]!.end)).toBe("$count");
    expect(issues[0]!.message).toContain('signal("count")');
    expect(issues[1]!.start).toBeGreaterThan(src.indexOf("dataText"));
  });
  it("checks html patches for ids, selectors and modes", () => {
    const src = `
      patchElements("""<div>no id</div>""")
      patchElements("<li>x</li>", selector = "#list", mode = ElementPatchMode.APPEND)
      patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
      removeElements(" ")
      executeScript("var s = '</script>'")
      respondElements(elements { div { id = "x" } })
    `;
    expect(codes(analyzeKotlin(src, opts))).toEqual(["missing-id", "mode-needs-selector", "blank-selector", "script-close"]);
  });
  it("ignores declarations, comments and dsl-built html", () => {
    const src = `// dataOnClick("$x")
      /* patchElements("<div>") */
      fun dataOnClick(expression: String) = 1
      patchElements(selector = "#a") { li { +"x" } }`;
    expect(analyzeKotlin(src, opts)).toEqual([]);
  });
  it("treats raw strings and dotted signals", () => {
    const src = 'dataEffect("""\n  ${\'$\'}user.name = "x"\n""")';
    expect(analyzeKotlin(src, opts)).toEqual([]);
  });
});

describe("analyzeHtml", () => {
  it("validates a template", () => {
    const src = `<form data-on:submit__prevent="@post('/save')"><input data-bind:search data-indicator="busy" data-onn:x="1"></form>`;
    expect(codes(analyzeHtml(src, opts))).toEqual(["unknown-attribute"]);
  });
});

describe("signals", () => {
  it("collects from kotlin and html", () => {
    const kt = `dataSignals("count" to 0, "user" to mapOf("name" to "")); dataBind("search"); signal("open"); patchSignals(Search(q = "x"))
      @Serializable data class Search(val query: String = "", val page: Int = 1)`;
    expect([...collectSignals(kt, "kotlin")].sort()).toEqual(["count", "name", "open", "page", "query", "search", "user"]);
    const html = `<div data-signals="{count: 1, open: false}" data-bind:first-name data-text="$other.x"></div>`;
    expect([...collectSignals(html, "html")].sort()).toEqual(["count", "firstName", "open", "other.x"]);
  });
});

describe("sse", () => {
  it("parses frames across chunks and decodes datastar events", () => {
    const p = new SseParser();
    const a = p.feed("event: datastar-patch-elements\nid: 7\ndata: selector #a\ndata: elements <div>\ndata: elements ");
    expect(a).toEqual([]);
    const b = p.feed("</div>\n\n: ping\n\nevent: datastar-patch-signals\r\ndata: signals {\"n\":1}\r\n\r\n");
    expect(b.length).toBe(3);
    const f = decodeDatastar(b[0]!);
    expect(f.args).toEqual({ selector: "#a", elements: "<div>\n</div>" });
    expect(f.id).toBe("7");
    expect(b[1]!.comments).toEqual(["ping"]);
    expect(decodeDatastar(b[2]!).args.signals).toBe('{"n":1}');
  });
  it("merge-patches signals", () => {
    expect(mergePatch({ a: 1, u: { n: "x", e: "y" } }, { a: null, u: { e: null, t: 1 }, l: [1] })).toEqual({ u: { n: "x", t: 1 }, l: [1] });
  });
});
