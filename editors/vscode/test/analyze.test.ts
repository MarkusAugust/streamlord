import { describe, it } from "node:test";
import assert from "node:assert/strict";

const expect = (actual: unknown) => ({
  toEqual: (expected: unknown) => assert.deepEqual(actual, expected),
  toBe: (expected: unknown) => assert.equal(actual, expected),
  toBeNull: () => assert.equal(actual, null),
  toContain: (needle: string) => assert.ok(typeof actual === "string" && actual.includes(needle), `expected ${JSON.stringify(actual)} to contain ${JSON.stringify(needle)}`),
  toBeGreaterThan: (n: number) => assert.ok((actual as number) > n, `expected ${actual} > ${n}`),
});
import { readFileSync } from "node:fs";
import { analyzeHtml, analyzeKotlin, htmlStringAt } from "../src/analyze.ts";
import { readKotlinStringAt } from "../src/kotlinStrings.ts";
import { findCallSites } from "../src/scanner.ts";
import { validateExpression } from "../src/expression.ts";
import { validateMarkup } from "../src/markup.ts";
import { collectSignals } from "../src/signals.ts";
import { collectSelectors } from "../src/selectors.ts";
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

  it("reads multi-dollar literals, where a single dollar is text", () => {
    const src = 'f($$"a$b$$c$${e}${\'$\'}$$$d")';
    const s = readKotlinStringAt(src, 2)!;
    expect(s.dollars).toBe(2);
    expect(s.start).toBe(2);
    expect(s.text).toBe("a$b__kt____kt__${'$'}$__kt__");
    expect(s.interpolations.map((i) => [i.kind, i.text])).toEqual([["simple", "c"], ["braced", "e"], ["simple", "d"]]);
    expect(src.slice(s.interpolations[0]!.start, s.interpolations[0]!.end)).toBe("$$c");
    expect(src.slice(s.interpolations[2]!.start, s.interpolations[2]!.end)).toBe("$$d");
    const raw = readKotlinStringAt('$$"""<i>$x</i>"""', 0)!;
    expect(raw.raw).toBe(true);
    expect(raw.text).toBe("<i>$x</i>");
    expect(readKotlinStringAt("$$x", 0)).toBeNull();
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
  it("keeps scanning after raw strings, nested comments and char literals", () => {
    const src = `
      s.patchElements("""<div>a</div>""")
      s.patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)
      val c = '"'
      /* outer /* inner */ still comment: patchElements("<p>") */
      s.patchElements("""<div id="a"><p>x</div>""")
      s.removeElements(" ")`;
    expect(codes(analyzeKotlin(src, opts))).toEqual(["missing-id", "mode-needs-selector", "unclosed", "blank-selector"]);
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

describe("free-standing html strings", () => {
  const src = `@Language("HTML")
fun side(tilstand: Tilstand): String = """
  <div data-signals="{count: 0}" data-on:click__debunce.500ms="@post('/x')">
    <span data-text="\${'$'}count"></span>
    <p data-text="$navn"></p>
    <i>$title</i>
  </div>
"""
val row = "<li data-onn:click=\\"x()\\">x</li>"
val notHtml = "count: $count"
val sql = """select * from x where a < 1 and b = $b"""
s.patchElements("""<div data-onn:x="1"></div>""")
dataText("$count")`;

  it("checks attributes like a template, and interpolation inside expressions as an error", () => {
    const issues = analyzeKotlin(src, opts);
    expect(codes(issues)).toEqual(["unknown-attribute", "missing-id", "kotlin-interpolation", "kotlin-interpolation", "kotlin-interpolation", "unknown-modifier", "unknown-attribute"]);
    const [inExpression, inText] = issues.filter((i) => i.code === "kotlin-interpolation" && src.slice(i.start, i.end) !== "$count");
    expect(src.slice(inExpression!.start, inExpression!.end)).toBe("$navn");
    expect(inExpression!.severity).toBe("error");
    expect(inExpression!.fixes!.map((f) => f.title)).toEqual(["Make it a $$ literal, where $navn is a signal", "Escape as ${'$'}navn"]);
    expect(inText!.severity).toBe("hint");
    expect(inText!.fixes).toBe(undefined);
    expect(src.slice(inText!.start, inText!.end)).toBe("$title");
  });

  it("finds html strings by content, marker or call site", () => {
    const at = (needle: string) => htmlStringAt(src, src.indexOf(needle) + 1);
    expect(at("data-signals")?.raw).toBe(true);
    expect(at("data-onn:click")?.raw).toBe(false);
    expect(at("count: $count")).toBeNull();
    expect(at("select *")).toBeNull();
    expect(at("data-onn:x")?.raw).toBe(true);
    expect(at('"$count")')).toBeNull();
    expect(htmlStringAt(src, src.indexOf("fun side"))).toBeNull();
  });

  it("trusts multi-dollar literals: no trap, but everything else is still checked", () => {
    const free = 'fun side() = $$"""<div data-text="$count" data-signals="{n: $$n}" data-onn:x="1"></div>"""';
    expect(codes(analyzeKotlin(free, opts))).toEqual(["unknown-attribute"]);
    const call = 's.patchElements($$"""<div id="a" data-text="$count +"></div>""")';
    const [syntax] = analyzeKotlin(call, opts);
    expect(codes([syntax!])).toEqual(["expression-syntax"]);
    expect(call.slice(syntax!.start, syntax!.end).length).toBeGreaterThan(0);
    expect(syntax!.start).toBeGreaterThan(call.indexOf("$count"));
    expect(codes(analyzeKotlin('dataOnClick($$"$count++"); dataOn("keydown", $$"$open = !$open")', opts))).toEqual([]);
    expect(codes(analyzeKotlin('dataOnClick($$"$count +")', opts))).toEqual(["expression-syntax"]);
    expect(findCallSites('s.patchElements($$"""<b></b>""", selector = "#x")', new Set(["patchElements"]))[0]!.args.map((a) => a.string !== null)).toEqual([true, true]);
  });

  it("honours the injection marker without a leading tag", () => {
    const marked = `// language=HTML\nval fragment = """Hei <b data-onn:y="1">du</b>"""`;
    expect(codes(analyzeKotlin(marked, opts))).toEqual(["unknown-attribute"]);
    expect(codes(analyzeKotlin(marked.replace("// language=HTML\n", ""), opts))).toEqual([]);
    const annotated = `@Language("html")\nprivate val head = """\n  \${'$'}{title}<title data-text="$t"></title>\n"""`;
    expect(codes(analyzeKotlin(annotated, opts))).toEqual(["kotlin-interpolation"]);
    const param = `fun wrap(@Language("HTML") html: String) = "a <b data-onn:x='1'>" + html`;
    expect(analyzeKotlin(param, opts)).toEqual([]);
  });
});

describe("analyzeHtml", () => {
  it("validates a template", () => {
    const src = `<form data-on:submit__prevent="@post('/save')"><input data-bind:search data-indicator="busy" data-onn:x="1"></form>`;
    expect(codes(analyzeHtml(src, opts))).toEqual(["unknown-attribute"]);
  });

  it("matches the expectations written in the jvm template fixtures", () => {
    const fixture = (name: string) => codes(analyzeHtml(readFileSync(new URL(`./fixtures/${name}`, import.meta.url), "utf8"), opts));
    expect(fixture("template.jte")).toEqual(["unknown-modifier", "expression-syntax"]);
    expect(fixture("template.ftl")).toEqual(["unknown-attribute", "missing-key"]);
    expect(fixture("template.vm")).toEqual(["modifier-args"]);
    expect(fixture("template.mustache")).toEqual(["pro-attribute", "expression-syntax"]);
  });

  it("skips template tags and comments when checking completeness", () => {
    const ftl = `<#if x><div id="a" data-text="\${y}"></div><#else><p id="b">no</p></#if><#-- <span> --><%-- <b> --%>`;
    expect(validateMarkup(ftl, { requireIds: true, prefix: "data-", checkAttributes: true })).toEqual([]);
    expect(validateMarkup(`@if(x)<div id="a"></div>@endif`, { requireIds: true, prefix: "data-", checkAttributes: true })).toEqual([]);
  });
});

describe("signals", () => {
  it("collects from kotlin and html", () => {
    const kt = `dataSignals("count" to 0, "user" to mapOf("name" to "")); dataBind("search"); signal("open"); patchSignals(Search(q = "x"))
      mapOf("notASignal" to 1); removeSignals("gone", "away")
      @Serializable data class Search(val query: String = "", val page: Int = 1)`;
    expect([...collectSignals(kt, "kotlin")].sort()).toEqual(["away", "count", "gone", "name", "open", "page", "query", "search", "user"]);
    const html = `<div data-signals="{count: 1, open: false}" data-bind:first-name data-text="$other.x"></div>`;
    expect([...collectSignals(html, "html")].sort()).toEqual(["count", "firstName", "open", "other.x"]);
    const raw = `fun side() = """<div data-signals="{draft: ''}" data-bind:search data-indicator="busy" data-text="$kotlinTemplate"></div>"""`;
    expect([...collectSignals(raw, "kotlin")].sort()).toEqual(["busy", "draft", "search"]);
  });
});

describe("selectors", () => {
  it("collects ids and classes from kotlin dsl and html", () => {
    const src = `div { id = "feed"; classes = setOf("card", "dark") }
      patchElements("""<ul id="list" class="menu open"><li id="\${item.id}" class="\$cls"></li></ul>""")
      <span id="counter" class="big"></span>`;
    const s = collectSelectors(src);
    expect([...s.ids].sort()).toEqual(["counter", "feed", "list"]);
    expect([...s.classes].sort()).toEqual(["big", "card", "dark", "menu", "open"]);
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
