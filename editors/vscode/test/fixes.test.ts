import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { analyzeHtml, analyzeKotlin } from "../src/analyze.ts";
import type { Fix, Issue } from "../src/expression.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };

/** Apply one fix to source text. */
const apply = (src: string, fix: Fix) => src.slice(0, fix.start) + fix.text + src.slice(fix.end);
const withCode = (issues: Issue[], code: string) => issues.filter((i) => i.code === code);

describe("quick fixes in kotlin", () => {
  it("offers the dsl helper and the two escapes for an interpolated signal", () => {
    const src = `dataOnClick("$count++")`;
    const [issue] = withCode(analyzeKotlin(src, opts), "kotlin-interpolation");
    assert.deepEqual(issue!.fixes!.map((f) => f.title), ['Use increment("count")', "Make it a $$ literal, where $count is a signal", "Escape as ${'$'}count", "Escape as \\$count"]);
    assert.equal(apply(src, issue!.fixes![0]!), `dataOnClick(increment("count"))`);
    assert.equal(apply(src, issue!.fixes![1]!), `dataOnClick($$"$count++")`);
    assert.equal(apply(src, issue!.fixes![2]!), `dataOnClick("\${'$'}count++")`);
    assert.equal(apply(src, issue!.fixes![3]!), `dataOnClick("\\$count++")`);
  });

  it("turns an html string into a $$ literal, keeping the kotlin templates kotlin", () => {
    const src = `fun f(d: String, t: String) = """<h2>$t \${'$'}x \${t.length}</h2><span data-text="$d"></span>"""`;
    const issues = withCode(analyzeKotlin(src, opts), "kotlin-interpolation");
    const onD = issues.find((i) => src.slice(i.start, i.end) === "$d")!;
    assert.equal(onD.severity, "error");
    assert.deepEqual(onD.fixes!.map((f) => f.title), ["Make it a $$ literal, where $d is a signal", "Escape as ${'$'}d"]);
    assert.equal(apply(src, onD.fixes![0]!), `fun f(d: String, t: String) = $$"""<h2>$$t $x $\${t.length}</h2><span data-text="$d"></span>"""`);
    assert.deepEqual(analyzeKotlin(apply(src, onD.fixes![0]!), opts), [], "the result is clean");
    const onT = issues.find((i) => src.slice(i.start, i.end) === "$t")!;
    assert.equal(onT.severity, "hint");
  });

  it("recognises the other helpers and skips them for mixed expressions", () => {
    const helper = (src: string) => withCode(analyzeKotlin(src, opts), "kotlin-interpolation")[0]!.fixes![0]!.title;
    assert.equal(helper(`dataText("$user.name")`), 'Use signal("user.name")');
    assert.equal(helper(`dataShow("!$open")`), 'Use not("open")');
    assert.equal(helper(`dataOnClick("$n--")`), 'Use decrement("n")');
    assert.equal(helper(`dataOnClick("$open = !$open")`), 'Use toggle("open")');
    assert.equal(helper(`dataOnClick("$a + $b")`), "Make it a $$ literal, where $a is a signal");
    const both = withCode(analyzeKotlin(`dataOnClick("$a + $b")`, opts), "kotlin-interpolation");
    assert.equal(apply(`dataOnClick("$a + $b")`, both[0]!.fixes![0]!), `dataOnClick($$"$a + $$b")`);
    assert.equal(apply(`dataOnClick("$a + $b")`, both[1]!.fixes![0]!), `dataOnClick($$"$$a + $b")`);
    const raw = withCode(analyzeKotlin(`dataText("""$count""")`, opts), "kotlin-interpolation")[0]!;
    assert.ok(!raw.fixes!.some((f) => f.title.startsWith("Escape as \\\\")), "no backslash escape in raw strings");
  });

  it("adds a selector before the mode argument", () => {
    const src = `s.patchElements("<li>x</li>", mode = ElementPatchMode.APPEND)`;
    const [issue] = withCode(analyzeKotlin(src, opts), "mode-needs-selector");
    assert.equal(apply(src, issue!.fixes![0]!), `s.patchElements("<li>x</li>", selector = "", mode = ElementPatchMode.APPEND)`);
  });

  it("fixes markup inside kotlin strings with source offsets", () => {
    const src = `s.patchElements("""<div>x</div><p id="a" data-on:click__debunce.500ms="y()"></p>""")`;
    const issues = analyzeKotlin(src, opts);
    const id = withCode(issues, "missing-id")[0]!;
    assert.equal(apply(src, id.fixes![0]!), src.replace("<div>", `<div id="div">`));
    const mod = withCode(issues, "unknown-modifier")[0]!;
    assert.equal(apply(src, mod.fixes![0]!), src.replace("__debunce", "__debounce"));
    const action = withCode(analyzeKotlin(`dataOnClick("@Post('/x')")`, opts), "unknown-action")[0]!;
    assert.equal(apply(`dataOnClick("@Post('/x')")`, action.fixes![0]!), `dataOnClick("@post('/x')")`);
  });

  it("links diagnostics to the reference", () => {
    const issues = analyzeKotlin(`dataOnClick("@Post('/x')"); s.patchElements("""<div data-signal:x="1" data-on-intersect__threshold.150="y()"></div>""")`, opts);
    assert.ok(issues.every((i) => i.link?.startsWith("https://data-star.dev/")), JSON.stringify(issues.map((i) => [i.code, i.link])));
  });
});

describe("quick fixes in html", () => {
  it("renames attributes and modifiers and adds a missing duration", () => {
    const src = `<div data-signal:foo="1" data-on:click__debunce="x()" data-on-intersect__debounce="y()"></div>`;
    const issues = analyzeHtml(src, opts);
    const attr = withCode(issues, "unknown-attribute")[0]!;
    assert.equal(apply(src, attr.fixes![0]!), src.replace("data-signal:foo", "data-signals:foo"));
    const mod = withCode(issues, "unknown-modifier")[0]!;
    assert.equal(apply(src, mod.fixes![0]!), src.replace("__debunce", "__debounce"));
    const dur = withCode(issues, "modifier-args")[0]!;
    assert.equal(apply(src, dur.fixes![0]!), src.replace("__debounce=", "__debounce.500ms="));
  });
});

describe("generated files", () => {
  it("html custom data and snippets are current and well-formed", () => {
    const data = JSON.parse(readFileSync(new URL("../html-customdata.json", import.meta.url), "utf8"));
    assert.equal(data.version, 1.1);
    const names = data.globalAttributes.map((a: { name: string }) => a.name);
    assert.ok(names.includes("data-on") && names.includes("data-persist"));
    assert.ok(data.globalAttributes.every((a: { description: { value: string }; references: unknown[] }) => a.description.value.length > 10 && a.references.length === 1));
    const snippets = JSON.parse(readFileSync(new URL("../snippets/html.json", import.meta.url), "utf8"));
    assert.equal(snippets["data-on"].body, 'data-on:${1:click}="${2:expression}"');
    const kotlin = JSON.parse(readFileSync(new URL("../snippets/kotlin.json", import.meta.url), "utf8"));
    assert.ok(Object.keys(kotlin).length >= 10);
  });
});
