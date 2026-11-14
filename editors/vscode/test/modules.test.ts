import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { MockDocument } from "./vscode-mock.ts";
import { complete, diagnose, hover, signalsOf } from "../src/analysis.ts";
import { StreamlordCompletionProvider } from "../src/completion.ts";
import { StreamlordHoverProvider } from "../src/hover.ts";
import { placesIn } from "../src/signalIndex.ts";
import { StreamlordSignatureHelpProvider } from "../src/signatureHelp.ts";

const DSL = "import io.github.markusaugust.streamlord.html.*\n";

describe("which Streamlord modules a Kotlin file can use", () => {
  // Whether the file can call the DSL, by whether the DSL's helper is documented there.
  const canCallDsl = (imports: string) => hover(imports + "\nfun f() = dataText(x)", imports.length + 12, true, "data-") !== null;

  it("calls the DSL only through its import or from inside its package", () => {
    assert.equal(canCallDsl("fun f() = 1"), false);
    assert.equal(canCallDsl(DSL), true);
    assert.equal(canCallDsl("import io.github.markusaugust.streamlord.html.signal as s\n"), true);
    assert.equal(canCallDsl("package io.github.markusaugust.streamlord.html\n"), true);
    assert.equal(canCallDsl("import kotlinx.html.*\n"), false);
    assert.equal(canCallDsl("\uFEFF" + DSL), true);
    // As the colours read it: an import in a comment or a raw string imports nothing.
    assert.equal(canCallDsl("/*\n" + DSL + "*/\n"), false);
    assert.equal(canCallDsl('val sample = """\n' + DSL + '"""\n'), false);
  });

  // Whether the project has core no text proves, so core stays unknown and is taken for there, but for the official SDK.
  it("has no Streamlord where the file uses the official SDK alone", () => {
    const official = "import dev.datastar.kotlin.sdk.blocking.ServerSentEventGenerator\n";
    const streamlord = "import io.github.markusaugust.streamlord.ktor.respondDatastar\n";
    // The markup handed to patchElements is Streamlord's to read only where the file may have core.
    const handed = `fun f(sse: Any) = sse.patchElements("data-text")`;
    const inMarkup = (imports: string) => hover(imports + handed, (imports + handed).indexOf("data-text") + 2, true, "data-") !== null;
    assert.equal(canCallDsl(official), false);
    assert.equal(inMarkup(official), false);
    assert.equal(inMarkup(official + streamlord), true);
    assert.equal(canCallDsl(official + streamlord), false);
  });
});

describe("a DSL-named call in a file without the DSL", () => {
  // `$count` is Kotlin's own template, the trap; `\$total` hands Datastar the signal.
  const src = `fun f() { dataText("$count + 1"); dataText("\\$total"); dataOnClick("@post('/x')") }`;
  const doc = new MockDocument(src, "kotlin");

  it("gets no trap and reads no signal", () => {
    const issues = (text: string) => diagnose(text, true, "data-", [], null, null);
    assert.deepEqual(issues(src), []);
    assert.ok(issues(DSL + src).some((i) => i.message.includes("Kotlin")), issues(DSL + src).map((i) => i.message).join());
    assert.deepEqual(placesIn(src, "kotlin", "data-").names.reads, []);
    assert.deepEqual(placesIn(DSL + src, "kotlin", "data-").names.reads.map((r) => r.name), ["total"]);
  });

  it("gets no completion, hover or signature help", () => {
    const index = { allDefinitions: () => signalsOf(`<div data-signals:count="0"></div>`, false, "data-").definitions, allSelectors: () => ({ ids: new Set(), classes: new Set() }) };
    const completion = new StreamlordCompletionProvider(index as never, () => "data-");
    assert.deepEqual(completion.provideCompletionItems(doc as never, doc.positionAt(src.indexOf("count")) as never), []);
    const hover = new StreamlordHoverProvider(() => "data-");
    assert.equal(hover.provideHover(doc as never, doc.positionAt(src.indexOf("@post") + 2) as never), null);
    assert.equal(hover.provideHover(doc as never, doc.positionAt(src.indexOf("dataText") + 2) as never), null);
    const help = new StreamlordSignatureHelpProvider(() => "data-");
    assert.equal(help.provideSignatureHelp(doc as never, doc.positionAt(src.indexOf("'/x'")) as never), null);
  });

  it("is completed and documented where the file imports the DSL", () => {
    const withDsl = new MockDocument(DSL + src, "kotlin");
    const hover = new StreamlordHoverProvider(() => "data-");
    const doc = hover.provideHover(withDsl as never, withDsl.positionAt(withDsl.text.indexOf("@post") + 2) as never);
    assert.match(String((doc?.contents as unknown as { value: string }).value), /Kotlin: `post\(\)`/);
  });
});

describe("kotlinx.html's attributes form and HTML strings, whatever the file has", () => {
  const index = { allDefinitions: () => signalsOf(`<div data-signals:count="0"></div>`, false, "data-").definitions, allSelectors: () => ({ ids: new Set(), classes: new Set() }) };
  const completion = new StreamlordCompletionProvider(index as never, () => "data-");
  const labels = (src: string, at: number) => completion.provideCompletionItems(new MockDocument(src, "kotlin") as never, new MockDocument(src, "kotlin").positionAt(at) as never).map((i) => String(i.label));

  it("completes the value of an attributes index", () => {
    const src = `fun FlowContent.f() { span { attributes["data-text"] = "\\$co" } }`;
    assert.ok(labels(src, src.indexOf('" }')).includes("$count"));
  });

  it("completes an HTML string, and not the markup handed to a call that is no Streamlord's", () => {
    const html = `fun f() = """<p data-text="\${'$'}co"></p>"""`;
    assert.ok(labels(html, html.indexOf('"></p>')).includes("$count"));
    const handed = `import dev.datastar.kotlin.sdk.blocking.ServerSentEventGenerator\nfun f(sse: ServerSentEventGenerator) = sse.patchElements("data-text")`;
    assert.deepEqual(labels(handed, handed.indexOf('")')), []);
  });

  it("offers no ids or classes in a call of the official SDK", () => {
    const src = `import dev.datastar.kotlin.sdk.blocking.ServerSentEventGenerator\nfun f(g: ServerSentEventGenerator) = g.removeElements("#")`;
    const selectors = { allSelectors: () => ({ ids: new Set(["feed"]), classes: new Set<string>() }) };
    const provider = new StreamlordCompletionProvider({ ...index, ...selectors } as never, () => "data-");
    const at = (text: string) => provider.provideCompletionItems(new MockDocument(text, "kotlin") as never, new MockDocument(text, "kotlin").positionAt(text.indexOf('#"') + 1) as never).map((i) => String(i.label));
    assert.deepEqual(at(src), []);
    assert.deepEqual(at(`fun f(s: Any) = s.removeElements("#")`), ["#feed"]);
  });

  it("documents an action there, without the Kotlin helper", () => {
    const src = `fun FlowContent.f() { button { attributes["data-on:click"] = "@post('/x')" } }`;
    const doc = new MockDocument(src, "kotlin");
    const h = new StreamlordHoverProvider(() => "data-").provideHover(doc as never, doc.positionAt(src.indexOf("@post") + 2) as never);
    const value = String((h?.contents as unknown as { value: string }).value);
    assert.match(value, /@post\(uri, options\?\)/);
    assert.doesNotMatch(value, /Kotlin:/);
  });
});
