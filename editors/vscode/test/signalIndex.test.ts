import { describe, it } from "node:test";
import assert from "node:assert/strict";
import * as vscode from "vscode";
import { files, Uri, workspace } from "./vscode-mock.ts";
import { SignalIndex } from "../src/signalIndex.ts";

/** An open document as the index reads it: a uri, a language, a version and the text. */
const doc = (path: string, languageId: string, text: string, version = 1, scheme = "file") =>
  ({ uri: { ...Uri.file(path), scheme }, languageId, version, getText: () => text }) as unknown as vscode.TextDocument;

/** Install a stand-in for a workspace event, and hand back what fires it. */
function capture<T>(name: "onDidRenameFiles" | "onDidDeleteFiles"): { fire: (e: T) => void; restore: () => void } {
  const original = workspace[name];
  let listener: ((e: T) => void) | null = null;
  (workspace as Record<string, unknown>)[name] = (l: (e: T) => void) => {
    listener = l;
    return { dispose: () => {} };
  };
  return { fire: (e) => listener?.(e), restore: () => ((workspace as Record<string, unknown>)[name] = original) };
}

const encode = (text: string) => new TextEncoder().encode(text);
const settle = () => new Promise((resolve) => setImmediate(resolve));

describe("signal index", () => {
  it("reads a template file as markup, by extension or by a configured language", () => {
    const index = new SignalIndex(new Set(["jinja-html"]));
    index.update(doc("/t/page.jte", "plaintext", `<div data-signals:query="''"></div>`));
    index.update(doc("/t/page.j2", "jinja-html", `<div data-signals:open="false"></div>`));
    index.update(doc("/t/notes.txt", "plaintext", `<div data-signals:nope="1"></div>`));
    assert.deepEqual([...index.allDefinitions()].sort(), ["open", "query"]);
  });

  it("says when definitions change, and only then", () => {
    const index = new SignalIndex(new Set());
    let fired = 0;
    index.onDidChangeDefinitions(() => fired++);
    index.update(doc("/t/a.html", "html", `<div data-signals:count="0"></div>`, 1));
    index.update(doc("/t/a.html", "html", `<div data-signals:count="0"></div>`, 1));
    index.update(doc("/t/a.html", "html", `<div data-signals:count="1"></div>`, 2));
    assert.equal(fired, 1);
    index.update(doc("/t/a.html", "html", `<div data-signals:total="1"></div>`, 3));
    assert.equal(fired, 2);
    assert.deepEqual([...index.allDefinitions()], ["total"]);
  });

  it("rebuilds aside, reads templates from disk and keeps an open document's edits", async () => {
    files.set("/w/page.peb", new TextEncoder().encode(`<div data-signals:fromDisk="1"></div>`));
    files.set("/w/open.html", new TextEncoder().encode(`<div data-signals:stale="1"></div>`));
    const findFiles = workspace.findFiles;
    workspace.findFiles = async () => [Uri.file("/w/page.peb"), Uri.file("/w/open.html")];
    workspace.textDocuments = [doc("/w/open.html", "html", `<div data-signals:edited="1"></div>`) as never];
    try {
      const index = new SignalIndex(new Set());
      index.update(doc("/w/old.html", "html", `<div data-signals:old="1"></div>`));
      const building = index.rebuild();
      // Until the new index is in, the old one answers.
      assert.deepEqual([...index.allDefinitions()], ["old"]);
      await building;
      assert.ok(index.ready);
      assert.deepEqual([...index.allDefinitions()].sort(), ["edited", "fromDisk"]);
    } finally {
      workspace.findFiles = findFiles;
      workspace.textDocuments = [];
      files.clear();
    }
  });

  it("reads a renamed file under its new name", async () => {
    const rename = capture<{ files: { oldUri: unknown; newUri: unknown }[] }>("onDidRenameFiles");
    try {
      const index = new SignalIndex(new Set());
      index.update(doc("/r/page.html", "html", `<div data-signals:count="0"></div>`));
      files.set("/r/index.html", encode(`<div data-signals:count="0"></div>`));
      rename.fire({ files: [{ oldUri: Uri.file("/r/page.html"), newUri: Uri.file("/r/index.html") }] });
      await settle();
      assert.deepEqual([...index.allDefinitions()], ["count"]);
    } finally {
      rename.restore();
      files.clear();
    }
  });

  it("forgets every file under a deleted folder", () => {
    const remove = capture<{ files: unknown[] }>("onDidDeleteFiles");
    try {
      const index = new SignalIndex(new Set());
      index.update(doc("/d/templates/form.html", "html", `<div data-signals:email="''"></div>`));
      index.update(doc("/d/templates-old.html", "html", `<div data-signals:kept="1"></div>`));
      remove.fire({ files: [Uri.file("/d/templates")] });
      assert.deepEqual([...index.allDefinitions()], ["kept"]);
    } finally {
      remove.restore();
    }
  });

  it("leaves a git diff or an untitled buffer out", () => {
    const index = new SignalIndex(new Set());
    index.update(doc("/g/page.html", "html", `<div data-signals:oldName="1"></div>`, 1, "git"));
    index.update(doc("Untitled-1", "html", `<div data-signals:scratch="1"></div>`, 1, "untitled"));
    assert.deepEqual([...index.allDefinitions()], []);
  });
});
