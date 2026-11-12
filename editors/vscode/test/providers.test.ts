import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { MockDocument, Position, Range, type CodeAction } from "./vscode-mock.ts";
import { IssueStore, StreamlordCodeActionProvider } from "../src/codeActions.ts";
import { StreamlordCompletionProvider } from "../src/completion.ts";
import { StreamlordHoverProvider } from "../src/hover.ts";
import { StreamlordSignatureHelpProvider } from "../src/signatureHelp.ts";
import { diagnose, signalsOf } from "../src/analysis.ts";
import { assertGrowsLinearly } from "./growth.ts";

/** A stand-in for SignalIndex over a fixed set of documents. */
class FakeIndex {
  private files = new Map<string, { text: string; lang: "kotlin" | "html" }>();
  add(uri: string, text: string, lang: "kotlin" | "html") {
    this.files.set(uri, { text, lang });
    return this;
  }
  allDefinitions() {
    const out = new Set<string>();
    for (const f of this.files.values()) for (const s of signalsOf(f.text, f.lang === "kotlin").definitions) out.add(s);
    return [...out];
  }
  allSelectors() {
    const ids = new Set<string>();
    const classes = new Set<string>();
    for (const f of this.files.values()) {
      const s = signalsOf(f.text, f.lang === "kotlin");
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
      "<a href=/x?a=b data-attr:title=`x` id=y data-",
    ]) {
      assert.ok(complete(pdoc(tag), 0, tag.length, index).some((i) => i.label === "data-on"), tag);
    }
    // After an equals sign and white space the browser reads a value, not a name.
    const value = "<a href=/x id= data-";
    assert.deepEqual(complete(pdoc(value), 0, value.length, index), [], value);
    const escaped = `val html = "<div class=\\"card\\" data-`;
    assert.ok(complete(new MockDocument(escaped, "kotlin"), 0, escaped.length, index).some((i) => i.label === "data-on"), escaped);
    // CodeQL's input, which never finished before. It ends on a name the caret completes, so the
    // whole reading is timed.
    const hostile = (n: number) => "<A " + "!= ".repeat(n) + "data-on:click__";
    assertGrowsLinearly("completing after a run of !=", 200, hostile, (src) => {
      assert.ok(complete(pdoc(src), 0, src.length, index).some((i) => i.label === "debounce"), "modifiers at the end of the hostile input");
    });
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

/** An item's name, whether its label is a string or carries a detail. */
const name = (i: { label: unknown }) => (typeof i.label === "string" ? i.label : (i.label as { label: string }).label);

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
    const labels = items.map(name);
    assert.ok(labels.includes("retryInterval") && !labels.includes("retry"), labels.join());
    // Its default shows after its name, as IntelliJ shows it.
    assert.deepEqual(items.find((i) => name(i) === "retryInterval")?.label, { label: "retryInterval", detail: " = 1000" });
    assert.match(String((items.find((i) => name(i) === "retryInterval")?.documentation as { value: string }).value), /\*\*retryInterval\*\*/);
  });

  it("offers the options in a template and in a Kotlin DSL string", () => {
    assert.ok(at(`<div data-on:click="@get('/x', {ret`, "pebble").items.some((i) => name(i) === "retryInterval"), "pebble");
    const kotlin = new MockDocument(`${DSL}fun f() { dataOnClick("@get('/x', {ret})") }`, "kotlin");
    const provider = new StreamlordCompletionProvider(index as never, () => "data-");
    const items = provider.provideCompletionItems(kotlin as never, kotlin.positionAt(kotlin.text.indexOf("})")) as never);
    assert.ok(items.some((i) => name(i) === "retryInterval"), "kotlin");
  });

  it("writes the colon after an option only where none follows", () => {
    const doc = new MockDocument(`<div data-on:click="@get('/x', {retr: 'never'})"></div>`, "html");
    const provider = new StreamlordCompletionProvider(index as never, () => "data-");
    const items = provider.provideCompletionItems(doc as never, doc.positionAt(doc.text.indexOf(":", 35)) as never);
    assert.equal(items.find((i) => name(i) === "retry")?.insertText, "retry");
    assert.equal(at(`<div data-on:click="@get('/x', {retr`).items.find((i) => name(i) === "retry")?.insertText, "retry: ");
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
    const tag = "<a " + "!= ".repeat(40);
    const hostile = (lines: number) => `${tag}\n`.repeat(lines) + tag + "data-on:click__";
    assertGrowsLinearly("completing after lines of tags never closed", 6, hostile, (src) => {
      assert.ok(labels(src, "pebble").includes("debounce"), "modifiers at the end of the long document");
    });
  });

  it("completes after a stray quote elsewhere in the markup", () => {
    const oneLine = labels(`<div data-on:click__`);
    assert.ok(oneLine.includes("debounce"), oneLine.join());
    assert.deepEqual(labels(`<img alt=it's> <div data-on:click__`), oneLine, "an unquoted value holding a quote");
    assert.deepEqual(labels(`<p a"b>x</p>\n<div\n  data-on:click__`), oneLine, "a quote inside an attribute name");
  });

  it("reads the text of script, textarea and title as text", () => {
    const oneLine = labels(`<div data-on:click__`);
    assert.deepEqual(labels(`<script>if (i<n) {/* don't */}</script> <div data-on:click__`), oneLine, "script");
    assert.deepEqual(labels(`<textarea>x<y isn't</textarea>\n<div data-on:click__`), oneLine, "textarea");
    assert.deepEqual(labels(`<title>a<b don't</title>\n<div data-on:click__`), oneLine, "title");
  });

  it("offers nothing inside a script or a style", () => {
    assert.deepEqual(labels(`<script>\n if (a<b &&\n  data-on:click__`), [], "script");
    assert.deepEqual(labels(`<style>\n a<b\n  data-on:click__`), [], "style");
  });

  it("ends a comment at the short closings the browser takes", () => {
    const oneLine = labels(`<div data-on:click__`);
    assert.deepEqual(labels(`<!--> <div\n data-on:click__`), oneLine, "<!-->");
    assert.deepEqual(labels(`<!---> <div\n data-on:click__`), oneLine, "<!--->");
  });

  it("reads a / in an unquoted value as the value's, and one outside a value as a new attribute", () => {
    const modifiers = labels(`<div data-on:click__`);
    for (const inValue of ["<a href=/api/data-", "<a href=x/data-on:click__"]) {
      assert.deepEqual(labels(inValue, "pebble"), [], `${inValue} in a template`);
      assert.deepEqual(inKotlin(inValue), [], `${inValue} in a Kotlin string`);
    }
    for (const outside of ["<div/data-on:click__", "<div a/data-on:click__", `<div a="x"/data-on:click__`]) {
      assert.deepEqual(labels(outside), modifiers, `${outside} in html`);
      assert.deepEqual(inKotlin(outside), modifiers, `${outside} in a Kotlin string`);
    }
  });

  it("starts a new attribute name right after a quoted value", () => {
    const modifiers = labels(`<div data-on:click__`);
    for (const markup of [`<div a="x"data-on:click__`, `<div a='x'data-on:click__`, `<div a="x y"data-on:click__`]) {
      assert.deepEqual(labels(markup), modifiers, `${markup} in html`);
      assert.deepEqual(inKotlin(markup), modifiers, `${markup} in a Kotlin string`);
    }
  });

  it("reads Kotlin's escaped quotes in an ordinary string as quotes", () => {
    const src = `val x = "<div data-show=\\"\\$a > 1\\" data-on:click__"`;
    assert.ok(labels(src, "kotlin", src.length - 1).includes("debounce"), src);
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
    // Each parameter by where it stands in the signature, as VS Code highlights it.
    assert.deepEqual(h?.signatures[0]?.parameters.map((p) => p.label), [[5, 8], [10, 18]]);
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

describe("code action provider", () => {
  /** The actions at `offset` of a Kotlin document linted as the extension lints it, and what applying one leaves. */
  function actionsAt(text: string, offset: number, end = offset) {
    const doc = new MockDocument(text, "kotlin");
    const issues = new IssueStore();
    issues.set(doc.uri as never, diagnose(text, true, "data-", [], null));
    const range = new Range(doc.positionAt(offset), doc.positionAt(end));
    const actions = new StreamlordCodeActionProvider(issues, () => ({ prefix: "data-", constants: [] })).provideCodeActions(doc as never, range as never, { diagnostics: [] } as never) as unknown as CodeAction[];
    const applied = (action: CodeAction) =>
      [...(action.edit?.replacements ?? [])]
        .sort((a, b) => doc.offsetAt(b.range.start) - doc.offsetAt(a.range.start))
        .reduce((out, r) => out.slice(0, doc.offsetAt(r.range.start)) + r.text + out.slice(doc.offsetAt(r.range.end)), text);
    return { actions, applied };
  }

  const DOMAIN = "io.github.markusaugust.streamlord.core.domain";
  const head = `import ${DOMAIN}.Trusted\nimport ${DOMAIN}.interpolate\n\nfun rootHtml(k: WidgetContext, customCss: String? = null, bodyHtml: String): String =\n    `;
  /** The literal of the case that found the defects, with each value written as given. */
  const widget = (root: string, css: string, content: string, body: string, events: string) =>
    `"""\n    <div id="${root}" data-theme="light" data-lt-boundary="">\n        <style>${css}</style>\n        <div id="${content}">\n            ${body}\n        </div>\n        <div id="${events}" hidden></div>\n    </div>\n    """.trimIndent()`;
  const REWRITE = "Build this string with interpolate(...)";
  const ESCAPES = `${REWRITE}, which escapes the values`;

  it("offers the rewrite from each value and leaves one call with the style's value as Trusted", () => {
    const css = "${customCss.orEmpty()}";
    const values = ["${k.rootId}", css, "${k.contentId}", "$bodyHtml", "${k.eventsId}"] as const;
    const text = `${head}${widget(...values)}\n`;
    const rewritten = `${head}interpolate(${widget("%s", "%s", "%s", "%s", "%s")}, k.rootId, Trusted(customCss.orEmpty()), k.contentId, bodyHtml, k.eventsId).text\n`;
    for (const value of values) {
      const title = value === css ? `Build this string with interpolate(...), with ${css} as Trusted(...)` : ESCAPES;
      const { actions, applied } = actionsAt(text, text.indexOf(value) + 1);
      // The diagnostic's quick fix is the rewrite there, so no refactoring stands beside it.
      const rewrites = actions.filter((a) => a.title.startsWith(REWRITE));
      assert.deepEqual(rewrites.map((a) => [a.title, a.kind?.value]), [[title, "quickfix"]], value);
      assert.equal(applied(rewrites[0] as CodeAction), rewritten, value);
    }
  });

  const card = 'fun card(name: String): String = "<p class=\\"x\\">$name</p>"';
  const carded = `import ${DOMAIN}.interpolate\n\nfun card(name: String): String = interpolate("<p class=\\"x\\">%s</p>", name).text`;

  it("offers the rewrite once, as a refactoring, at a caret away from the warning", () => {
    const { actions, applied } = actionsAt(card, card.indexOf("class"));
    const rewrites = actions.filter((a) => a.title.startsWith(REWRITE));
    assert.deepEqual(rewrites.map((a) => [a.title, a.kind?.value, a.isPreferred ?? false]), [[ESCAPES, "refactor.rewrite", false]]);
    assert.equal(applied(rewrites[0] as CodeAction), carded);
  });

  it("offers it for a selection inside the string, and not for one that reaches out of it", () => {
    const inside = actionsAt(card, card.indexOf("<p"), card.indexOf("class") + 5);
    assert.deepEqual(inside.actions.filter((a) => a.title.startsWith(REWRITE)).map((a) => a.kind?.value), ["refactor.rewrite"]);
    assert.equal(inside.applied(inside.actions[0] as CodeAction), carded);
    assert.deepEqual(actionsAt(card, card.indexOf("String ="), card.indexOf("class")).actions, []);
  });

  it("offers every fix of every diagnostic over the whole file, each tied to its own diagnostic", () => {
    const text = readFileSync(new URL("./fixtures/real/kotlin/ViewMistakes.kt", import.meta.url), "utf8");
    const doc = new MockDocument(text, "kotlin");
    const found = diagnose(text, true, "data-", [], null);
    const issues = new IssueStore();
    issues.set(doc.uri as never, found);
    // What VS Code hands the provider: every diagnostic the range touches, as the extension made them.
    const diagnostics = found.map((i) => ({ source: "streamlord", code: i.code, range: new Range(doc.positionAt(i.start), doc.positionAt(i.end)) }));
    const whole = new Range(doc.positionAt(0), doc.positionAt(text.length));
    const actions = new StreamlordCodeActionProvider(issues, () => ({ prefix: "data-", constants: [] })).provideCodeActions(doc as never, whole as never, { diagnostics } as never) as unknown as CodeAction[];
    const got = actions
      .filter((a) => a.kind?.value === "quickfix")
      .map((a) => {
        const [d] = (a.diagnostics ?? []) as typeof diagnostics;
        return `${d?.code} ${d ? `${doc.offsetAt(d.range.start)}-${doc.offsetAt(d.range.end)}` : "none"} ${a.title}`;
      });
    assert.deepEqual(got, found.flatMap((i) => (i.fixes ?? []).map((f) => `${i.code} ${i.start}-${i.end} ${f.title}`)));
    // Line 8, `dataText("$count")`, has four.
    const line8 = text.indexOf('dataText("$count")');
    assert.equal(got.filter((g) => g.startsWith(`kotlin-interpolation ${line8 + 10}-`)).length, 4);
  });

  it("offers nothing where no value of the string is escaped", () => {
    const text = 'fun total(count: Int): String = "<p>total $count</p>"';
    assert.deepEqual(actionsAt(text, text.indexOf("total $")).actions, []);
  });
});

describe("playground html template", () => {
  it("matches the expectations written in the file", () => {
    const html = readFileSync(new URL("./fixtures/template.html", import.meta.url), "utf8");
    const codes = diagnose(html, false, "data-", [], null).map((i) => i.code);
    assert.deepEqual(codes, ["unknown-modifier", "modifier-args", "missing-key", "unknown-attribute", "expression-syntax", "pro-attribute", "pro-attribute", "prefix-mismatch"]);
  });
});
