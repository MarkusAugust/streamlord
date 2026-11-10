import { beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import * as vscode from "vscode";
import { files, Position, settings, unreadable, Uri, workspace, type OpenDocument } from "./vscode-mock.ts";
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
    unreadable.clear();
    settings.clear();
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

  const request = (name: string) => ({ name, url: `/${name}`, method: "GET", signals: "", headers: "" });

  // Reading it as a missing file would write a fresh file over requests that are there.
  it("does not take a file it cannot read for a missing one", async () => {
    const written = bytes(HAND_WRITTEN);
    files.set(REQUESTS, written);
    unreadable.set(REQUESTS, Object.assign(new Error("EACCES: permission denied"), { code: "NoPermissions" }));
    const s = store();
    const file = await s.readFile();
    const error = ".streamlord/inspector.json cannot be read: EACCES: permission denied";
    assert.deepEqual([file.requests, file.error], [[], error]);
    const refused = { message: `${error} Fix the file before saving, so the requests in it are not lost.` };
    await assert.rejects(s.save(request("a")), refused);
    await assert.rejects(s.delete("feed"), refused);
    assert.equal(files.get(REQUESTS), written);
  });

  it("creates no file where one cannot be read", async () => {
    unreadable.set(REQUESTS, new Error("the disk is gone"));
    const s = store();
    assert.equal((await s.readFile()).error, ".streamlord/inspector.json cannot be read: the disk is gone");
    await assert.rejects(s.save(request("a")), { message: ".streamlord/inspector.json cannot be read: the disk is gone Fix the file before saving, so the requests in it are not lost." });
    assert.equal(files.has(REQUESTS), false);
  });

  it("reads a file that is not found as no file", async () => {
    const file = await store().readFile();
    assert.deepEqual([file.requests, file.error, file.skipped], [[], undefined, []]);
  });

  // Two saves that both read the file before either writes would lose one of them.
  it("makes changes one at a time, so two saves at once are both kept", async () => {
    files.set(REQUESTS, bytes('{\n  "version": 1,\n  "requests": []\n}\n'));
    const s = store();
    await Promise.all([s.save(request("a")), s.save(request("b"))]);
    assert.equal(disk(REQUESTS), `{\n  "version": 1,\n  "requests": [\n    ${entry("a", "/a")},\n    ${entry("b", "/b")}\n  ]\n}\n`);
    await Promise.all([s.delete("a"), s.save(request("c")), s.delete("b")]);
    assert.deepEqual((await s.saved()).map((r) => r.name), ["c"]);
  });

  it("goes on with the next change after one that was refused", async () => {
    files.set(REQUESTS, bytes("[]"));
    const s = store();
    const refused = s.save(request("a"));
    files.set(REQUESTS, bytes('{"requests": []}'));
    const next = s.save(request("b"));
    await assert.rejects(refused);
    await next;
    assert.deepEqual((await s.saved()).map((r) => r.name), ["b"]);
  });

  it("reads and writes the file the setting names", async () => {
    settings.set("streamlord.inspector.requestsFile", "tools/requests.json");
    const s = store();
    assert.equal(s.requestsPathProblem(), null);
    await s.save(request("a"));
    assert.deepEqual([...files.keys()], [ENV, "/ws/tools/requests.json"]);
  });

  it("refuses a save and a delete when the setting names a path with a problem, and writes nothing", async () => {
    const env = files.get(ENV);
    const problems = {
      "../elsewhere/requests.json": "The requests file ../elsewhere/requests.json leaves the workspace: use a path without .. in it.",
      "tools\\requests.json": "The requests file tools\\requests.json holds a backslash: write the path with /.",
      ".streamlord/./env.json": "The requests file cannot be .streamlord/env.json: that file holds the variables.",
    };
    for (const [path, problem] of Object.entries(problems)) {
      settings.set("streamlord.inspector.requestsFile", path);
      const s = store();
      assert.equal(s.requestsPathProblem(), problem);
      await assert.rejects(s.save(request("a")), { message: `${problem} Nothing was saved.` });
      await assert.rejects(s.delete("a"), { message: `${problem} Nothing was saved.` });
      assert.deepEqual([...files.keys()], [ENV]);
      assert.equal(files.get(ENV), env);
    }
  });
});
