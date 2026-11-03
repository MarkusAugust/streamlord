import { describe, it } from "node:test";
import assert from "node:assert/strict";
import * as vscode from "vscode";
import { Position, workspace, type OpenDocument } from "./vscode-mock.ts";
import { SignalNavigationProvider } from "../src/signalNavigation.ts";
import { placesIn, type Places } from "../src/signalIndex.ts";
import { collectSignalDefinitions } from "../src/signals.ts";

const dsl = "import io.github.markusaugust.streamlord.html.*\n";

/** Open documents by path, as the editor holds them, with an index over them. */
function workspaceOf(files: Record<string, string>) {
  const docs = Object.entries(files).map(([path, text]) => {
    const doc = {
      uri: { scheme: "file", path, toString: () => `file://${path}` },
      languageId: path.endsWith(".kt") ? "kotlin" : "html",
      text,
      getText: () => doc.text,
      offsetAt: (p: Position) => doc.text.split("\n").slice(0, p.line).reduce((n, l) => n + l.length + 1, 0) + p.character,
      positionAt: (offset: number) => {
        const before = doc.text.slice(0, offset).split("\n");
        return new Position(before.length - 1, before[before.length - 1]!.length);
      },
    };
    return doc;
  });
  workspace.textDocuments = docs as unknown as OpenDocument[];
  const language = (path: string) => (path.endsWith(".kt") ? "kotlin" : "html");
  const index = {
    allDefinitions: () => new Set(docs.flatMap((d) => [...collectSignalDefinitions(d.text, language(d.uri.path))])),
    allPlaces: async () => new Map<string, Places>(docs.map((d) => [d.uri.toString(), placesIn(d.text, language(d.uri.path), "data-")])),
  };
  const provider = new SignalNavigationProvider(index as never, () => "data-");
  /** The document at `path` and the position of the first `|` in its text, which is taken out. */
  const at = (path: string) => {
    const doc = docs.find((d) => d.uri.path === path)!;
    const offset = doc.text.indexOf("|");
    doc.text = doc.text.replace("|", "");
    return [doc as unknown as vscode.TextDocument, doc.positionAt(offset) as unknown as vscode.Position] as const;
  };
  /** Each location as its file and the text it covers. */
  const show = (locations: vscode.Location[] | null) =>
    (locations ?? [])
      .map((l) => {
        const doc = docs.find((d) => d.uri.toString() === l.uri.toString())!;
        return `${doc.uri.path} ${doc.text.slice(doc.offsetAt(l.range.start as never), doc.offsetAt(l.range.end as never))}`;
      })
      .sort();
  return { provider, at, show, docs };
}

describe("signal navigation", () => {
  it("goes to every place the workspace defines a signal", async () => {
    const w = workspaceOf({
      "/w/page.html": `<div data-signals:count="0"></div>`,
      "/w/Handler.kt": dsl + `fun h() = patchSignals(mapOf("count" to 1))`,
      "/w/other.html": `<span data-text="$co|unt + 1"></span>`,
    });
    const [doc, pos] = w.at("/w/other.html");
    assert.deepEqual(w.show(await w.provider.provideDefinition(doc, pos)), ["/w/Handler.kt count", "/w/page.html count"]);
  });

  it("reads each part of a path as its own signal", async () => {
    const w = workspaceOf({
      "/w/page.html": `<div data-signals="{user: {name: ''}}"></div>`,
      "/w/View.kt": dsl + `fun f() = span { dataText("\${'$'}user.na|me.length") }`,
    });
    const [doc, pos] = w.at("/w/View.kt");
    assert.deepEqual(w.show(await w.provider.provideDefinition(doc, pos)), ["/w/page.html name"]);
  });

  it("finds every place a signal is defined and read", async () => {
    const w = workspaceOf({
      "/w/page.html": `<div data-signals:count="0"><span data-text="$count"></span></div>`,
      "/w/View.kt": dsl + `fun f() = span { dataText("\${'$'}count + \${'$'}other") }`,
      "/w/other.html": `<b data-text="$co|unt"></b>`,
    });
    const [doc, pos] = w.at("/w/other.html");
    const refs = await w.provider.provideReferences(doc, pos, { includeDeclaration: true });
    assert.deepEqual(w.show(refs), ["/w/View.kt count", "/w/other.html count", "/w/page.html count", "/w/page.html count"]);
    const reads = await w.provider.provideReferences(doc, pos, { includeDeclaration: false });
    assert.equal(reads!.length, 3);
  });

  it("renames a signal as each place wants it", async () => {
    const w = workspaceOf({
      "/w/page.html": `<div data-signals:count="0"><span data-text="$count"></span></div>`,
      "/w/View.kt": dsl + `fun h() = patchSignals(mapOf("count" to 1))`,
      "/w/other.html": `<b data-text="$co|unt + 1"></b>`,
    });
    const [doc, pos] = w.at("/w/other.html");
    assert.equal(w.provider.prepareRename(doc, pos).placeholder, "count");
    const edit = await w.provider.provideRenameEdits(doc, pos, "totalCount");
    await workspace.applyEdit(edit as never);
    assert.deepEqual(
      w.docs.map((d) => d.text),
      [`<div data-signals:total-count="0"><span data-text="$totalCount"></span></div>`, dsl + `fun h() = patchSignals(mapOf("totalCount" to 1))`, `<b data-text="$totalCount + 1"></b>`],
    );
  });

  it("stops a rename at a place that cannot hold the new name", async () => {
    const w = workspaceOf({ "/w/page.html": `<div data-signals:my-value__case.snake="0" data-text="$my_va|lue"></div>` });
    const [doc, pos] = w.at("/w/page.html");
    await assert.rejects(w.provider.provideRenameEdits(doc, pos, "myTitle"), /'my-value' in page.html cannot be written so that it names 'myTitle'/);
    await workspace.applyEdit((await w.provider.provideRenameEdits(doc, pos, "my_title")) as never);
    assert.equal(w.docs[0]!.text, `<div data-signals:my-title__case.snake="0" data-text="$my_title"></div>`);
  });
});
