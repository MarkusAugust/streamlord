import { beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import * as vscode from "vscode";
import { files, Position, Uri, workspace, type OpenDocument } from "./vscode-mock.ts";
import { RequestStore } from "../src/requestStore.ts";
import { RunningServers } from "../src/serverLog.ts";

const ENV = "/ws/.streamlord/env.json";
const REQUESTS = "/ws/.streamlord/inspector.json";
const bytes = (s: string) => new TextEncoder().encode(s);
const disk = (path = ENV) => new TextDecoder().decode(files.get(path));

/** A requests file as a person writes one: members and an entry the inspector does not know, and a layout of its own. */
const HAND_WRITTEN = `{
  "version": 1,
  "team": "blue",
  "requests": [
    {
      "name": "feed",
      "url": "{{baseUrl}}/feed",
      "method": "POST",
      "signals": { "search": "ash", "n": 1.0 },
      "headers": { "X-Id": "7" },
      "note": "written by hand"
    },
    { "name": "no url here" },
    { "name": "plain", "url": "http://h/feed" }
  ]
}
`;
const entry = (name: string, url: string) => `{\n      "name": "${name}",\n      "url": "${url}",\n      "method": "GET",\n      "signals": "",\n      "headers": ""\n    }`;
const NOT_A_REQUESTS_FILE = '.streamlord/inspector.json is not a requests file: it must be an object with a "requests" list.';

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

const store = (running = new RunningServers()) =>
  new RequestStore({ workspaceState: { get: <T>(_k: string, d: T) => d, update: async () => {} } } as unknown as vscode.ExtensionContext, running);
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

  it("takes a running server's baseUrl when the env file sets none, and sets the file's on request", async () => {
    const running = new RunningServers();
    running.feed("1", "Motregning back (dev)", "Tomcat started on port 9102 (http) with context path '/'\n");
    const s = store(running);
    assert.equal(await baseUrl(s), "http://disk:1");
    await s.useBaseUrl("http://localhost:9102");
    assert.equal(disk(), '{"baseUrl": "http://localhost:9102"}');
    files.set(ENV, bytes('{"params": {}}'));
    const found = (await s.environment()).vars[0];
    assert.deepEqual(found, { name: "baseUrl", value: "http://localhost:9102", source: "running", origin: "Motregning back (dev)" });
  });

  it("keeps what else a hand-written requests file holds through a save and a delete", async () => {
    files.set(REQUESTS, bytes(HAND_WRITTEN));
    const s = store();
    assert.deepEqual((await s.saved()).map((r) => r.name), ["feed", "plain"]);

    await s.save({ name: "alpha", url: "/a", method: "GET", signals: "", headers: "" });
    const withAlpha = HAND_WRITTEN.replace('    {\n      "name": "feed"', `    ${entry("alpha", "/a")},\n    {\n      "name": "feed"`);
    assert.equal(disk(REQUESTS), withAlpha);

    const feed = (await s.saved()).find((r) => r.name === "feed");
    assert.deepEqual(feed, { name: "feed", url: "{{baseUrl}}/feed", method: "POST", signals: '{ "search": "ash", "n": 1.0 }', headers: "X-Id: 7" });
    await s.save({ ...feed!, url: "{{baseUrl}}/feed2" });
    const withFeed2 = withAlpha.replace('"url": "{{baseUrl}}/feed"', '"url": "{{baseUrl}}/feed2"');
    assert.equal(disk(REQUESTS), withFeed2);

    await s.delete("PLAIN");
    assert.equal(disk(REQUESTS), withFeed2.replace(',\n    { "name": "plain", "url": "http://h/feed" }', ""));
    await s.delete("alpha");
    await s.delete("feed");
    assert.equal(disk(REQUESTS), '{\n  "version": 1,\n  "team": "blue",\n  "requests": [\n    { "name": "no url here" }\n  ]\n}\n');
  });

  it("writes the fresh file when there is none", async () => {
    await store().save({ name: "a", url: "/a", method: "GET", signals: "", headers: "" });
    assert.equal(disk(REQUESTS), `{\n  "version": 1,\n  "requests": [\n    ${entry("a", "/a")}\n  ]\n}\n`);
  });

  it("writes nothing when a request is saved as it was read", async () => {
    const written = bytes(HAND_WRITTEN);
    files.set(REQUESTS, written);
    const s = store();
    for (const request of await s.saved()) await s.save(request);
    assert.equal(files.get(REQUESTS), written);
  });

  it("writes nothing when the name to delete is not saved, and creates no file", async () => {
    const s = store();
    await s.delete("nowhere");
    assert.equal(files.has(REQUESTS), false);
    const written = bytes(HAND_WRITTEN);
    files.set(REQUESTS, written);
    await s.delete("nowhere");
    assert.equal(files.get(REQUESTS), written);
  });

  it("does not write over a requests file that is a list", async () => {
    const written = bytes('[{"name": "a", "url": "/a"}]\n');
    files.set(REQUESTS, written);
    const s = store();
    assert.equal((await s.readFile()).error, NOT_A_REQUESTS_FILE);
    const refused = { message: `${NOT_A_REQUESTS_FILE} Fix the file before saving, so the requests in it are not lost.` };
    await assert.rejects(s.save({ name: "b", url: "/b", method: "GET", signals: "", headers: "" }), refused);
    await assert.rejects(s.delete("a"), refused);
    assert.equal(files.get(REQUESTS), written);
  });

  it("reads the entries it leaves out by number, and none from a clean or a missing file", async () => {
    const s = store();
    assert.deepEqual((await s.readFile()).skipped, []);
    files.set(REQUESTS, bytes(HAND_WRITTEN));
    assert.deepEqual((await s.readFile()).skipped, ['Request 2 in .streamlord/inspector.json is not read: it has no "url".']);
    files.set(REQUESTS, bytes('{"requests": [{"name": "a", "url": "/a"}]}'));
    const clean = await s.readFile();
    assert.deepEqual([clean.requests.map((r) => r.name), clean.skipped], [["a"], []]);
  });
});
