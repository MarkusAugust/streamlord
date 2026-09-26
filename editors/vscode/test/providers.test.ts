import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { MockDocument, Position } from "./vscode-mock.ts";
import { StreamlordCompletionProvider } from "../src/completion.ts";
import { StreamlordHoverProvider } from "../src/hover.ts";
import { analyzeHtml } from "../src/analyze.ts";
import { collectSelectors } from "../src/selectors.ts";
import { collectSignals } from "../src/signals.ts";

/** A stand-in for SignalIndex over a fixed set of documents. */
class FakeIndex {
  private files = new Map<string, { text: string; lang: "kotlin" | "html" }>();
  add(uri: string, text: string, lang: "kotlin" | "html") {
    this.files.set(uri, { text, lang });
    return this;
  }
  all() {
    const out = new Set<string>();
    for (const f of this.files.values()) for (const s of collectSignals(f.text, f.lang)) out.add(s);
    return out;
  }
  forFile(uri: string) {
    const f = this.files.get(uri);
    return f ? collectSignals(f.text, f.lang) : new Set<string>();
  }
  selectorsForFile(uri: string) {
    const f = this.files.get(uri);
    return f ? collectSelectors(f.text) : { ids: new Set<string>(), classes: new Set<string>() };
  }
  allSelectors() {
    const ids = new Set<string>();
    const classes = new Set<string>();
    for (const f of this.files.values()) {
      const s = collectSelectors(f.text);
      for (const i of s.ids) ids.add(i);
      for (const c of s.classes) classes.add(c);
    }
    return { ids, classes };
  }
}

const kotlinSrc = `div {
    dataSignals("count" to 0, "open" to false)
    dataOnClick("$")
    dataOnClick("@")
    dataOnClick("evt.")
    s.patchElements("<li>x</li>", selector = "#", mode = ElementPatchMode.APPEND)
    s.removeElements(".")
    span { id = "counter"; classes = setOf("big") }
}`;
const other = `<div id="feed" class="card"></div><div data-signals="{busy: false}"></div>`;

function complete(doc: MockDocument, line: number, character: number, index: FakeIndex, prefix = "data-") {
  const provider = new StreamlordCompletionProvider(index as never, () => prefix);
  return provider.provideCompletionItems(doc as never, new Position(line, character) as never);
}

describe("completion provider", () => {
  const index = new FakeIndex().add("file:///mock.kotlin", kotlinSrc, "kotlin").add("file:///other.html", other, "html");
  const doc = new MockDocument(kotlinSrc, "kotlin");

  it("offers signals after $ with this file's first", () => {
    const items = complete(doc, 2, 18, index).sort((a, b) => (a.sortText ?? "").localeCompare(b.sortText ?? ""));
    const labels = items.map((i) => i.label);
    assert.deepEqual(labels.slice(0, 2), ["$count", "$open"]);
    assert.ok(labels.includes("$busy"));
    assert.equal(items[0]?.detail, "signal (this file)");
    assert.equal(items.find((i) => i.label === "$busy")?.detail, "signal (workspace)");
    assert.ok((items[0]?.sortText ?? "") < (items.find((i) => i.label === "$busy")?.sortText ?? ""));
  });

  it("offers actions after @ with pro last", () => {
    const items = complete(doc, 3, 18, index);
    const labels = items.map((i) => i.label);
    assert.equal(labels[0], "@get");
    assert.ok(labels.indexOf("@clipboard") > labels.indexOf("@peek"));
    assert.ok(items.every((i) => String(i.label).startsWith("@")));
    const post = items.find((i) => i.label === "@post");
    assert.match(String((post?.insertText as { value: string }).value), /^@post\('\$\{1:\/path\}'\)$/);
  });

  it("offers ids after # in a selector argument and classes after .", () => {
    const line5 = kotlinSrc.split("\n")[5]!;
    const hashItems = complete(doc, 5, line5.indexOf('"#"') + 2, index);
    assert.deepEqual(hashItems.map((i) => i.label), ["#counter", "#feed"]);
    assert.equal(hashItems[0]?.detail, "id (this file)");
    const line6 = kotlinSrc.split("\n")[6]!;
    const dotItems = complete(doc, 6, line6.indexOf('"."') + 2, index);
    assert.deepEqual(dotItems.map((i) => i.label), [".big", ".card"]);
  });

  it("offers nothing outside datastar strings", () => {
    assert.deepEqual(complete(doc, 1, 5, index), []);
  });

  it("offers attributes, modifiers and modifier values in html", () => {
    const html = `<form data-on:submit__prevent="@post('/x')">\n<input data-\n<div data-on:click__\n<div data-on:click__debounce.`;
    const hdoc = new MockDocument(html, "html");
    // Attribute names in plain HTML come from html-customdata.json through VS Code itself.
    assert.deepEqual(complete(hdoc, 1, 12, index), []);
    // In template languages VS Code's HTML service is absent, so the extension offers them.
    const pdoc = new MockDocument(html, "pebble");
    const attrs = complete(pdoc, 1, 12, index);
    assert.ok(attrs.some((i) => i.label === "data-on") && attrs.some((i) => i.label === "data-signals"));
    assert.ok((attrs.find((i) => i.label === "data-persist")?.sortText ?? "") > (attrs.find((i) => i.label === "data-on")?.sortText ?? ""));
    const mods = complete(hdoc, 2, 20, index);
    assert.ok(mods.some((i) => i.label === "debounce") && mods.some((i) => i.label === "outside"));
    const vals = complete(hdoc, 3, 29, index);
    assert.deepEqual(vals.map((i) => i.label), ["500ms", "1s", "300ms", "100ms"]);
  });

  it("offers signals inside html attribute values", () => {
    const hdoc = new MockDocument(`<div data-text="$`, "html");
    const items = complete(hdoc, 0, 17, index);
    assert.ok(items.some((i) => i.label === "$busy"));
  });

  it("gives html strings in kotlin the html side: attributes, modifiers, signals", () => {
    const src = `fun side() = """\n<div data-\n<div data-on:click__\n<div data-text="$\n"""\nval sql = """select data-\n"""`;
    const kdoc = new MockDocument(src, "kotlin");
    const attrs = complete(kdoc, 1, 10, index);
    assert.ok(attrs.some((i) => i.label === "data-on") && attrs.some((i) => i.label === "data-signals"), "attributes inside a free-standing html string");
    assert.ok(complete(kdoc, 2, 20, index).some((i) => i.label === "debounce"), "modifiers");
    assert.ok(complete(kdoc, 3, 17, index).some((i) => i.label === "$busy"), "signals in an attribute value");
    assert.deepEqual(complete(kdoc, 5, 25, index), [], "nothing in a string that is not html");
    const call = new MockDocument(`s.patchElements("""<li data-""")`, "kotlin");
    assert.ok(complete(call, 0, 28, index).some((i) => i.label === "data-on"), "attributes inside patchElements");
  });
});

describe("hover provider", () => {
  const hover = new StreamlordHoverProvider(() => "data-");

  it("documents dsl functions, attributes and actions", () => {
    const kdoc = new MockDocument(`div { dataOnClick("@post('/x')") }`, "kotlin");
    const h = hover.provideHover(kdoc as never, new Position(0, 8) as never);
    assert.match(String((h?.contents as unknown as { value: string }).value), /\*\*data-on\*\*/);
    const a = hover.provideHover(kdoc as never, new Position(0, 20) as never);
    assert.match(String((a?.contents as unknown as { value: string }).value), /@post\(uri, options\?\)/);
    const hdoc = new MockDocument(`<div data-on-intersect__once="x()">`, "html");
    const attr = hover.provideHover(hdoc as never, new Position(0, 10) as never);
    assert.match(String((attr?.contents as unknown as { value: string }).value), /data-on-intersect/);
    assert.match(String((attr?.contents as unknown as { value: string }).value), /__threshold/);
  });

  it("documents attributes inside html strings in kotlin", () => {
    const kdoc = new MockDocument(`val x = """<div data-on-intersect__once="x()">"""`, "kotlin");
    const attr = hover.provideHover(kdoc as never, new Position(0, 20) as never);
    assert.match(String((attr?.contents as unknown as { value: string }).value), /\*\*data-on-intersect\*\*/);
    const plain = new MockDocument(`val x = "data-on-intersect"`, "kotlin");
    assert.equal(hover.provideHover(plain as never, new Position(0, 15) as never), null);
  });
});

describe("playground html template", () => {
  it("matches the expectations written in the file", () => {
    const html = readFileSync(new URL("./fixtures/template.html", import.meta.url), "utf8");
    const codes = analyzeHtml(html, { prefix: "data-", checkHtmlAttributes: true }).map((i) => i.code);
    assert.deepEqual(codes, ["unknown-modifier", "modifier-args", "missing-key", "unknown-attribute", "expression-syntax", "pro-attribute", "pro-attribute", "prefix-mismatch"]);
  });
});
