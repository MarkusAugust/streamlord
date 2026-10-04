import { beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import * as vscode from "vscode";
import { files, Position, Uri, workspace, type OpenDocument } from "./vscode-mock.ts";
import { RequestStore } from "../src/requestStore.ts";

const ENV = "/ws/.streamlord/env.json";
const bytes = (s: string) => new TextEncoder().encode(s);
const disk = () => new TextDecoder().decode(files.get(ENV));

/** An open editor on the env file, holding `text` that is not saved. */
function open(text: string): OpenDocument {
  const doc: OpenDocument = {
    uri: Uri.file(ENV),
    text,
    getText: () => doc.text,
    offsetAt: (p: Position) => {
      const lines = doc.text.split("\n");
      return lines.slice(0, p.line).reduce((n, l) => n + l.length + 1, 0) + p.character;
    },
    positionAt: (offset: number) => {
      const before = doc.text.slice(0, offset).split("\n");
      return new Position(before.length - 1, (before[before.length - 1] ?? "").length);
    },
  };
  workspace.textDocuments.push(doc);
  return doc;
}

const store = () => new RequestStore({ workspaceState: { get: <T>(_k: string, d: T) => d, update: async () => {} } } as unknown as vscode.ExtensionContext);
const baseUrl = async (s: RequestStore) => (await s.environment()).vars.find((v) => v.name === "baseUrl")?.value;

describe("request store", () => {
  beforeEach(() => {
    files.clear();
    workspace.textDocuments.length = 0;
    workspace.workspaceFolders = [{ uri: Uri.file("/ws") }];
    files.set(ENV, bytes('{"baseUrl": "http://disk:1"}'));
  });

  it("reads the env file from disk when no editor has it open", async () => {
    assert.equal(await baseUrl(store()), "http://disk:1");
  });

  it("reads what an open editor holds, saved or not", async () => {
    open('{"baseUrl": "http://unsaved:2"}');
    assert.equal(await baseUrl(store()), "http://unsaved:2");
  });

  it("adds a key to the open editor, leaving the disk and the unsaved text alone", async () => {
    const doc = open('{"baseUrl": "http://unsaved:2"}');
    await store().defineVariables(["signals"]);
    assert.equal(doc.text, '{"baseUrl": "http://unsaved:2",\n  "signals": {}\n}');
    assert.equal(disk(), '{"baseUrl": "http://disk:1"}');
  });

  it("writes the disk when no editor has the file open", async () => {
    await store().defineVariables(["headers"]);
    assert.equal(disk(), '{"baseUrl": "http://disk:1",\n  "headers": {}\n}');
  });
});
