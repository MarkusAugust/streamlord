import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { MockDocument, Position } from "./vscode-mock.ts";
import { StreamlordCompletionProvider } from "../src/completion.ts";
import { StreamlordHoverProvider } from "../src/hover.ts";
import { StreamlordSignatureHelpProvider } from "../src/signatureHelp.ts";
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

/** The DSL's import, without which a DSL-named call is somebody else's. */
const DSL = "import io.github.markusaugust.streamlord.html.*\n";

const kotlinSrc = `${DSL}div {
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
    const items = complete(doc, 3, 18, index).sort((a, b) => (a.sortText ?? "").localeCompare(b.sortText ?? ""));
    const labels = items.map((i) => i.label);
    assert.deepEqual(labels.slice(0, 2), ["$count", "$open"]);
    assert.ok(labels.includes("$busy"));
    assert.equal(items[0]?.detail, "signal (this file)");
    assert.equal(items.find((i) => i.label === "$busy")?.detail, "signal (workspace)");
    assert.ok((items[0]?.sortText ?? "") < (items.find((i) => i.label === "$busy")?.sortText ?? ""));
  });

  it("offers actions after @ with pro last", () => {
    const items = complete(doc, 4, 18, index);
    const labels = items.map((i) => i.label);
    assert.ok(labels.indexOf("@clipboard") > labels.indexOf("@toggleAll"));
    assert.ok(items.every((i) => String(i.label).startsWith("@")));
    const post = items.find((i) => i.label === "@post");
    assert.match(String((post?.insertText as { value: string }).value), /^@post\('\$\{1:\/path\}'\)$/);
  });

  it("lists actions and modifiers by name, Pro last, as IntelliJ does", () => {
    // VS Code shows items by sortText, then by label; the provider's own order is the same.
    const shown = (items: { label: unknown; sortText?: string }[]) => {
      const key = (i: { label: unknown; sortText?: string }) => (i.sortText ?? String(i.label)).toLowerCase();
      const sorted = [...items].sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0)).map((i) => String(i.label));
      assert.deepEqual(items.map((i) => String(i.label)), sorted);
      return sorted;
    };
    assert.deepEqual(shown(complete(doc, 4, 18, index)), ["@delete", "@get", "@patch", "@peek", "@post", "@put", "@query", "@setAll", "@toggleAll", "@clipboard", "@fit", "@intl"]);
    const hdoc = new MockDocument(`<div data-on:click__\n<div data-on-intersect__\n<input data-bind:value__`, "html");
    assert.deepEqual(shown(complete(hdoc, 0, 20, index)), ["capture", "case", "debounce", "delay", "document", "once", "outside", "passive", "prevent", "stop", "throttle", "viewtransition", "window"]);
    assert.deepEqual(shown(complete(hdoc, 1, 24, index)), ["debounce", "delay", "exit", "full", "half", "once", "threshold", "throttle", "viewtransition"]);
    assert.deepEqual(shown(complete(hdoc, 2, 24, index)), ["case", "event", "prop"]);
  });

  it("offers ids after # in a selector argument and classes after .", () => {
    const line5 = kotlinSrc.split("\n")[6]!;
    const hashItems = complete(doc, 6, line5.indexOf('"#"') + 2, index);
    assert.deepEqual(hashItems.map((i) => i.label), ["#counter", "#feed"]);
    assert.equal(hashItems[0]?.detail, "id (this file)");
    const line6 = kotlinSrc.split("\n")[7]!;
    const dotItems = complete(doc, 7, line6.indexOf('"."') + 2, index);
    assert.deepEqual(dotItems.map((i) => i.label), [".big", ".card"]);
  });

  it("offers nothing outside datastar strings", () => {
    assert.deepEqual(complete(doc, 2, 5, index), []);
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

  it("offers patch only in data-on-signal-patch, where the client defines it", () => {
    const labels = (src: string, language: string, at = src.length, line = 0) => complete(new MockDocument(src, language), line, at, index).map((i) => i.label);
    assert.ok(labels(`<div data-on-signal-patch="pat`, "html").includes("patch"));
    assert.ok(labels(`${DSL}dataOnSignalPatch("pat")`, "kotlin", 22, 1).includes("patch"));
    const elsewhere = labels(`<div data-on:click="pat`, "html");
    assert.ok(elsewhere.includes("evt") && !elsewhere.includes("patch"), elsewhere.join());
  });

  it("finds the attribute name after other attributes, in time on any line", () => {
    const pdoc = (src: string) => new MockDocument(src, "pebble");
    for (const tag of [
      `<div id=x title="a b" data-x='c' hidden data-`,
      `<input {% if user.admin == true %}checked{% endif %} data-`,
      `<div {{ a == b ? 'x' : 'y' }} data-`,
      "<a href=/x?a=b data-attr:title=`x` id= data-",
    ]) {
      assert.ok(complete(pdoc(tag), 0, tag.length, index).some((i) => i.label === "data-on"), tag);
    }
    const escaped = `val html = "<div class=\\"card\\" data-`;
    assert.ok(complete(new MockDocument(escaped, "kotlin"), 0, escaped.length, index).some((i) => i.label === "data-on"), escaped);
    // CodeQL's input, as long as the 400 characters the provider reads, ending in a quote no value
    // regex takes; it never finished before.
    const hostile = "<A " + "!= ".repeat(132) + "'";
    const started = performance.now();
    complete(pdoc(hostile), 0, hostile.length, index);
    assert.ok(performance.now() - started < 1000, `${Math.round(performance.now() - started)} ms`);
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
    const typing = new MockDocument(`fun side() = $$"""\n<div data-`, "kotlin");
    assert.ok(complete(typing, 1, 10, index).some((i) => i.label === "data-on"), "while the string is still being typed");
  });
});

describe("help inside an expression", () => {
  const index = new FakeIndex().add("file:///other.html", other, "html");
  /** Where an item's range starts; the provider sets a plain range. */
  const startOf = (item: { range?: unknown }) => (item.range as { start: Position }).start;
  /** The items at the end of `src`, in a document of `language`. */
  const at = (src: string, language = "html") => {
    const doc = new MockDocument(src, language);
    const provider = new StreamlordCompletionProvider(index as never, () => "data-");
    return { doc, items: provider.provideCompletionItems(doc as never, doc.positionAt(src.length) as never) };
  };

  it("offers signals in a value over several lines and in single quotes", () => {
    assert.ok(at(`<div data-signals="{\n  a: 1,\n  b: $`).items.some((i) => i.label === "$busy"), "several lines");
    assert.ok(at(`<div data-text='$`).items.some((i) => i.label === "$busy"), "single quotes");
  });

  it("replaces the signal from its dollar as the source writes it", () => {
    const html = at(`<div data-show="$a &amp;&amp; $bu`);
    const busy = html.items.find((i) => i.label === "$busy");
    assert.equal(html.doc.offsetAt(startOf(busy!)), html.doc.text.length - 3);
    // In an ordinary Kotlin string the dollar is written \$, which stays as it is.
    const kotlin = at(`val x = "<div data-text=\\"\\$bu`, "kotlin");
    const k = kotlin.items.find((i) => i.label === "$busy");
    assert.equal(kotlin.doc.offsetAt(startOf(k!)), kotlin.doc.text.length - 3);
  });

  it("offers the options of a backend action not yet written, with what each does", () => {
    const { items } = at(`<div data-on:click="@get('/x', {retry: 'never', re`);
    const labels = items.map((i) => i.label);
    assert.ok(labels.includes("retryInterval") && !labels.includes("retry"), labels.join());
    assert.match(String((items.find((i) => i.label === "retryInterval")?.documentation as { value: string }).value), /\*\*retryInterval\*\*/);
  });

  it("offers the options in a template and in a Kotlin DSL string", () => {
    assert.ok(at(`<div data-on:click="@get('/x', {ret`, "pebble").items.some((i) => i.label === "retryInterval"), "pebble");
    const kotlin = new MockDocument(`${DSL}fun f() { dataOnClick("@get('/x', {ret})") }`, "kotlin");
    const provider = new StreamlordCompletionProvider(index as never, () => "data-");
    const items = provider.provideCompletionItems(kotlin as never, kotlin.positionAt(kotlin.text.indexOf("})")) as never);
    assert.ok(items.some((i) => i.label === "retryInterval"), "kotlin");
  });

  it("writes the colon after an option only where none follows", () => {
    const doc = new MockDocument(`<div data-on:click="@get('/x', {retr: 'never'})"></div>`, "html");
    const provider = new StreamlordCompletionProvider(index as never, () => "data-");
    const items = provider.provideCompletionItems(doc as never, doc.positionAt(doc.text.indexOf(":", 35)) as never);
    assert.equal(items.find((i) => i.label === "retry")?.insertText, "retry");
    assert.equal(at(`<div data-on:click="@get('/x', {retr`).items.find((i) => i.label === "retry")?.insertText, "retry: ");
  });

  it("offers DOM events and Datastar's own after data-on:", () => {
    const { doc, items } = at(`<button data-on:cl`);
    const click = items.find((i) => i.label === "click");
    assert.ok(click && items.some((i) => i.label === "datastar-fetch"));
    assert.equal(doc.offsetAt(startOf(click)), doc.text.length - 2);
  });
});

describe("an attribute on its own line", () => {
  const index = new FakeIndex().add("file:///other.html", other, "html");
  /** The labels offered at `at` in `src`, by default its end, in a document of `language`. */
  const labels = (src: string, language = "html", at = src.length) => {
    const doc = new MockDocument(src, language);
    const provider = new StreamlordCompletionProvider(index as never, () => "data-");
    return provider.provideCompletionItems(doc as never, doc.positionAt(at) as never).map((i) => String(i.label));
  };
  /** The labels offered at the end of a Kotlin raw string whose content is `html`. */
  const inKotlin = (html: string) => labels(`fun side() = """${html}"""`, "kotlin", `fun side() = """${html}`.length);

  it("offers the modifiers of data-on in a tag opened on the line above", () => {
    const oneLine = labels(`<input data-bind:query data-on:input__`);
    assert.ok(oneLine.includes("debounce"), oneLine.join());
    assert.deepEqual(labels(`<input data-bind:query\n       data-on:input__`), oneLine);
  });

  it("offers the duration values of a modifier in a tag opened on the line above", () => {
    const oneLine = labels(`<input data-bind:query data-on:input__debounce.`);
    assert.ok(oneLine.includes("500ms"), oneLine.join());
    assert.deepEqual(labels(`<input data-bind:query\n       data-on:input__debounce.`), oneLine);
  });

  it("offers the events after data-on: in a tag opened on the line above", () => {
    const oneLine = labels(`<input data-bind:query data-on:`);
    assert.ok(oneLine.includes("click") && oneLine.includes("datastar-fetch"), oneLine.join());
    assert.deepEqual(labels(`<input data-bind:query\n       data-on:`), oneLine);
  });

  it("offers the modifiers in a tag opened several lines above, with attributes on each line between", () => {
    const oneLine = labels(`<input type="search" data-bind:query class=wide data-on:input__`);
    assert.deepEqual(labels(`<input\n  type="search"\n  data-bind:query\n  class=wide\n  data-on:input__`), oneLine);
  });

  it("offers attribute names and modifiers in a Kotlin string, in a tag opened on the line above", () => {
    const oneLine = inKotlin(`\n<div class="card" data-`);
    assert.ok(oneLine.includes("data-on") && oneLine.includes("data-signals"), oneLine.join());
    assert.deepEqual(inKotlin(`\n<div class="card"\n     data-`), oneLine);
    const modifiers = inKotlin(`\n<div class="card" data-on:click__`);
    assert.ok(modifiers.includes("debounce"), modifiers.join());
    assert.deepEqual(inKotlin(`\n<div class="card"\n     data-on:click__`), modifiers);
  });

  it("reads no tag out of the Kotlin code before the string", () => {
    // The quotes in the code pair up so that `<b` reads as a tag reaching into the string.
    const sameLine = `val ok = a <b || e == "\\""; s.patchElements("""  data-on:click__""")`;
    assert.deepEqual(labels(sameLine, "kotlin", sameLine.indexOf(`""")`)), []);
    const lineAbove = `val ok = a <b || e == "\\""\ns.patchElements("""\n  data-on:click__""")`;
    assert.deepEqual(labels(lineAbove, "kotlin", lineAbove.indexOf(`""")`)), []);
  });

  it("reads quoted values on earlier lines as the browser does", () => {
    const oneLine = labels(`<div data-on:click__`);
    assert.ok(oneLine.includes("debounce"), oneLine.join());
    assert.deepEqual(labels(`<div data-show="$a > 1 && $b < 2"\n     data-text='"x"'\n     data-on:click__`), oneLine, "quotes holding >, < and the other quote");
    assert.deepEqual(labels(`<div data-show="$a\n > 1"\n     data-on:click__`), oneLine, "a value over two lines");
  });

  it("offers nothing in element content or in a comment", () => {
    assert.deepEqual(labels(`<div data-show="$a">\n  data-on:click__`), [], "after the tag is closed");
    assert.deepEqual(labels(`<!-- <div -->\n  data-on:click__`), [], "after a comment that held a tag");
    assert.deepEqual(labels(`<!-- <div\n  data-on:click__`), [], "inside a comment");
  });

  it("answers in time on a long document of tags never closed", () => {
    let hostile = "";
    while (hostile.length < 50_000) hostile += "<a " + "!= ".repeat(40) + "\n";
    hostile += "<a " + "!= ".repeat(40) + "'";
    const started = performance.now();
    labels(hostile, "pebble");
    const took = performance.now() - started;
    assert.ok(took < 250, `${Math.round(took)} ms`);
  });
});

describe("signature help", () => {
  const help = new StreamlordSignatureHelpProvider(() => "data-");
  const signature = (src: string, language: string, offset = src.length) => {
    const doc = new MockDocument(src, language);
    return help.provideSignatureHelp(doc as never, doc.positionAt(offset) as never);
  };

  it("shows the action's parameters with the one at the caret", () => {
    const h = signature(`<div data-on:click="@get('/x', `, "html");
    assert.equal(h?.signatures[0]?.label, "@get(uri, options?)");
    assert.deepEqual(h?.signatures[0]?.parameters.map((p) => p.label), ["uri", "options?"]);
    assert.equal(h?.activeParameter, 1);
    const src = `${DSL}div { dataOnClick("@post(") }`;
    assert.equal(signature(src, "kotlin", src.indexOf("(\")") + 1)?.activeParameter, 0);
    assert.equal(signature(`<div data-text="$count`, "html"), null);
  });
});

describe("hover provider", () => {
  const hover = new StreamlordHoverProvider(() => "data-");

  it("documents an option of a backend action", () => {
    const src = `<div data-on:click="@get('/x', {retryScaler: 3})">`;
    const doc = new MockDocument(src, "html");
    const h = hover.provideHover(doc as never, doc.positionAt(src.indexOf("retryScaler") + 3) as never);
    assert.match(String((h?.contents as unknown as { value: string }).value), /\*\*retryScaler\*\*/);
    const ksrc = `${DSL}fun f() { dataOnClick("@get('/x', {retryScaler: 3})") }`;
    const kdoc = new MockDocument(ksrc, "kotlin");
    const k = hover.provideHover(kdoc as never, kdoc.positionAt(ksrc.indexOf("retryScaler") + 3) as never);
    assert.match(String((k?.contents as unknown as { value: string }).value), /\*\*retryScaler\*\*/);
  });

  it("documents dsl functions, attributes and actions", () => {
    const kdoc = new MockDocument(`${DSL}div { dataOnClick("@post('/x')") }`, "kotlin");
    const h = hover.provideHover(kdoc as never, new Position(1, 8) as never);
    assert.match(String((h?.contents as unknown as { value: string }).value), /\*\*data-on\*\*/);
    const a = hover.provideHover(kdoc as never, new Position(1, 20) as never);
    assert.match(String((a?.contents as unknown as { value: string }).value), /@post\(uri, options\?\)/);
    const hdoc = new MockDocument(`<div data-on-intersect__once="x()">`, "html");
    const attr = hover.provideHover(hdoc as never, new Position(0, 10) as never);
    assert.match(String((attr?.contents as unknown as { value: string }).value), /data-on-intersect/);
    assert.match(String((attr?.contents as unknown as { value: string }).value), /__threshold/);
  });

  it("documents dataOnSignalPatch as the attribute its expression fills, not the filter it also writes", () => {
    const kdoc = new MockDocument(`${DSL}div { dataOnSignalPatch("console.log(patch)", SignalFilter.exclude("^_")) }`, "kotlin");
    const value = String((hover.provideHover(kdoc as never, new Position(1, 10) as never)?.contents as unknown as { value: string }).value);
    assert.match(value, /\*\*data-on-signal-patch\*\*/);
    assert.doesNotMatch(value, /data-on-signal-patch-filter/);
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
