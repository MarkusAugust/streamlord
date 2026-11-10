import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { Inspector } from "../src/inspector.ts";
import type { DatastarFrame } from "../src/sse.ts";
import { RequestStore } from "../src/requestStore.ts";
import { RunningServers } from "../src/serverLog.ts";
import { files, Position, settings, unreadable, Uri, workspace } from "./vscode-mock.ts";

/** An inspector with a stand-in panel that records what it posts to the page. */
function inspector(): { apply: (signals: string, onlyIfMissing?: string) => void; posted: { type: string; [k: string]: unknown }[] } {
  const posted: { type: string }[] = [];
  const context = { subscriptions: [] as unknown[] };
  const store = { onDidChange: () => ({ dispose() {} }) };
  // The panel and applyFrame are private to the class.
  const i = new Inspector(context as never, store as never, null as never) as any;
  i.panel = { webview: { postMessage: (m: { type: string }) => posted.push(m) } };
  const apply = (signals: string, onlyIfMissing?: string) => {
    const args: Record<string, string> = { signals, ...(onlyIfMissing !== undefined ? { onlyIfMissing } : {}) };
    const frame: DatastarFrame = { event: "datastar-patch-signals", id: null, retry: null, args, raw: "", receivedAt: 0 };
    i.applyFrame(frame);
  };
  return { apply, posted: posted as { type: string; [k: string]: unknown }[] };
}

describe("inspector signal store", () => {
  // The client keeps the keys a patch set before the one it fails on (signals.ts in 1.0.4).
  it("shows the keys set before a patch failed, and the reason", () => {
    const { apply, posted } = inspector();
    apply('{"a":[1,2]}');
    apply('{"b":1,"a":{"0":7,"length":-1},"c":2}');
    const signals = posted.filter((m) => m.type === "signals").map((m) => JSON.stringify(m.signals));
    assert.deepEqual(signals, ['{"a":[1,2]}', '{"a":[7,2],"b":1}']);
    const errors = posted.filter((m) => m.type === "error").map((m) => m.message);
    // The client leaves its batch open after a throw, so the bindings on the page stop redrawing
    // (measured in Chrome).
    assert.deepEqual(errors, [
      "The signal store cannot take this patch: Invalid array length -1 in a signal patch. " +
        "Datastar's client throws on it too, and the bindings already on the page then stop redrawing until it is reloaded, " +
        "though the signals still change, element patches still land and new bindings show the current value.",
    ]);
    apply('{"d":3}');
    assert.equal(JSON.stringify(posted.at(-1)?.signals), '{"a":[7,2],"b":1,"d":3}');
  });

  // A patch Streamlord refuses on purpose says so, and nothing about the page standing still.
  it("says when Streamlord refuses a patch on purpose", () => {
    const { apply, posted } = inspector();
    apply('{"a":[1]}');
    apply('{"b":1,"o":{"__proto__":{"x":1}},"c":2}');
    apply('{"a":{"20000":1}}');
    const signals = posted.filter((m) => m.type === "signals").map((m) => JSON.stringify(m.signals));
    assert.deepEqual(signals, ['{"a":[1]}', '{"a":[1],"b":1,"o":{}}', '{"a":[1],"b":1,"o":{}}']);
    const errors = posted.filter((m) => m.type === "error").map((m) => m.message);
    assert.deepEqual(errors, [
      "Streamlord refuses this signal patch on purpose: A key named __proto__ whose value is an object with keys mostly makes Datastar's client " +
        "throw at that key, and the bindings on the page then stop redrawing. Otherwise the client gives the object holding the key a foreign prototype, " +
        "which then throws on some later keys, replaces the object on a later patch, or with a second __proto__ writes into String.prototype. " +
        "Streamlord does not model it and refuses the patch. The store shown keeps the keys before it.",
      "Streamlord refuses this signal patch on purpose: Signal patch index 20000 is more than 10000 past the end of an array of 1: " +
        "the client takes it, and Streamlord does not fill memory with the empty slots. The store shown keeps the keys before it.",
    ]);
  });

  it("reads onlyIfMissing as the client reads it", () => {
    const { apply, posted } = inspector();
    apply('{"a":1}');
    apply('{"a":2,"b":2}', "\ufefftrue\u2028");
    apply('{"b":3,"c":3}', "\u001ctrue");
    assert.equal(JSON.stringify(posted.at(-1)?.signals), '{"a":1,"b":3,"c":3}');
  });
});

const REQUESTS = "/ws/.streamlord/inspector.json";

/** An inspector over a store that reads `text` as the requests file, null for none; `push` sends the page its requests. */
function inspectorOver(text: string | null) {
  files.clear();
  unreadable.clear();
  settings.clear();
  workspace.textDocuments.length = 0;
  workspace.workspaceFolders = [{ uri: Uri.file("/ws") }];
  const write = (t: string | null) => (t === null ? files.delete(REQUESTS) : files.set(REQUESTS, new TextEncoder().encode(t)));
  write(text);
  const context = { subscriptions: [] as unknown[], workspaceState: { get: <T>(_k: string, d: T) => d, update: async () => {} } };
  const store = new RequestStore(context as never, new RunningServers());
  const reads = { count: 0 };
  const readFile = store.readFile.bind(store);
  store.readFile = () => {
    reads.count++;
    return readFile();
  };
  const posted: { type: string; [k: string]: any }[] = [];
  // The panel and pushRequests are private to the class.
  const i = new Inspector(context as never, store, null as never) as any;
  i.panel = { webview: { postMessage: (m: { type: string }) => posted.push(m) } };
  const push = async () => {
    await i.pushRequests();
    return posted.at(-1)!;
  };
  return { push, write, reads };
}

describe("inspector requests file", () => {
  it("tells the page which entries of the file are not read", async () => {
    const { push } = inspectorOver('{"requests": [7, {"name": "feed", "url": "/feed"}, {"name": "no url"}]}');
    const m = await push();
    assert.equal(m.type, "requests");
    assert.deepEqual(m.requestProblems, [
      "Request 1 in .streamlord/inspector.json is not read: it is not an object.",
      'Request 3 in .streamlord/inspector.json is not read: it has no "url".',
    ]);
    assert.deepEqual(m.saved.map((r: { name: string }) => r.name), ["feed"]);
  });

  it("tells the page why a file that is a list is not read, and lists no requests", async () => {
    const { push } = inspectorOver('[{"name": "a", "url": "/a"}]');
    const m = await push();
    assert.deepEqual(m.requestProblems, ['.streamlord/inspector.json is not a requests file: it must be an object with a "requests" list.']);
    assert.deepEqual(m.saved, []);
  });

  it("has nothing to tell about a clean file or a missing one", async () => {
    const clean = await inspectorOver('{"requests": [{"name": "feed", "url": "/feed"}]}').push();
    assert.deepEqual(clean.requestProblems, []);
    assert.deepEqual(clean.saved.map((r: { name: string }) => r.name), ["feed"]);
    const missing = await inspectorOver(null).push();
    assert.deepEqual(missing.requestProblems, []);
    assert.deepEqual(missing.saved, []);
  });

  it("stops telling once the file is put right", async () => {
    const { push, write } = inspectorOver("[]");
    assert.equal((await push()).requestProblems.length, 1);
    write('{"requests": []}');
    assert.deepEqual((await push()).requestProblems, []);
  });

  // Two reads could see two files: the list from one and the problems from the other.
  it("reads the file once for the list and its problems", async () => {
    const { push, reads } = inspectorOver('{"requests": [7, {"name": "feed", "url": "/feed"}]}');
    await push();
    assert.equal(reads.count, 1);
  });

  // The default file is what the list then shows, and nothing else said so before a Save was refused.
  it("tells the page first when the setting names a path with a problem", async () => {
    const { push, write } = inspectorOver('{"requests": [7, {"name": "feed", "url": "/feed"}]}');
    settings.set("streamlord.inspector.requestsFile", "../elsewhere/requests.json");
    const problem = "The requests file ../elsewhere/requests.json leaves the workspace: use a path without .. in it. The default file is shown.";
    try {
      const m = await push();
      assert.deepEqual(m.requestProblems, [problem, "Request 1 in .streamlord/inspector.json is not read: it is not an object."]);
      assert.deepEqual(m.saved.map((r: { name: string }) => r.name), ["feed"]);
      write("[]");
      assert.deepEqual((await push()).requestProblems, [problem, '.streamlord/inspector.json is not a requests file: it must be an object with a "requests" list.']);
      write(null);
      assert.deepEqual((await push()).requestProblems, [problem]);
      settings.set("streamlord.inspector.requestsFile", ".streamlord/env.json");
      assert.deepEqual((await push()).requestProblems, ["The requests file cannot be .streamlord/env.json: that file holds the variables. The default file is shown."]);
      settings.clear();
      assert.deepEqual((await push()).requestProblems, []);
    } finally {
      settings.clear();
    }
  });
});

/** A glob as VS Code reads it: `**` is any folders, `*` any part of a name, `{a,b}` either. */
function globMatches(glob: string, path: string): boolean {
  let re = "";
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i]!;
    if (glob.startsWith("**/", i)) {
      re += "(?:.*/)?";
      i += 2;
    } else if (glob.startsWith("**", i)) {
      re += ".*";
      i += 1;
    } else if (c === "*") re += "[^/]*";
    else if (c === "?") re += "[^/]";
    else if (c === "{") re += "(?:";
    else if (c === "}") re += ")";
    else if (c === ",") re += "|";
    else re += c.replace(/[.+^$()|[\]\\]/g, "\\$&");
  }
  return new RegExp(`^${re}$`).test(path);
}

/**
 * An inspector whose store hears the editor: `type` is an edit of an open document, and `change` a file
 * changed on disk, told to each file watcher whose pattern takes its path. `lists` is the names last
 * sent to the page.
 */
function watchedInspector(text: string, path = REQUESTS) {
  files.clear();
  unreadable.clear();
  workspace.textDocuments.length = 0;
  workspace.workspaceFolders = [{ uri: Uri.file("/ws") }];
  files.set(path, new TextEncoder().encode(text));
  const edits: ((e: { document: { uri: unknown } }) => void)[] = [];
  const watchers: { pattern: unknown; listeners: ((u: unknown) => void)[] }[] = [];
  const { onDidChangeTextDocument, createFileSystemWatcher } = workspace;
  (workspace as Record<string, unknown>).onDidChangeTextDocument = (l: (e: { document: { uri: unknown } }) => void) => {
    edits.push(l);
    return { dispose: () => {} };
  };
  (workspace as Record<string, unknown>).createFileSystemWatcher = (pattern: unknown) => {
    const w = { pattern, listeners: [] as ((u: unknown) => void)[] };
    watchers.push(w);
    const on = (l: (u: unknown) => void) => {
      w.listeners.push(l);
      return { dispose: () => {} };
    };
    return { onDidChange: on, onDidCreate: on, onDidDelete: on, dispose: () => {} };
  };
  const restore = () => {
    workspace.onDidChangeTextDocument = onDidChangeTextDocument;
    workspace.createFileSystemWatcher = createFileSystemWatcher;
  };
  const context = { subscriptions: [] as unknown[], workspaceState: { get: <T>(_k: string, d: T) => d, update: async () => {} } };
  let store: RequestStore;
  try {
    store = new RequestStore(context as never, new RunningServers());
  } catch (e) {
    restore();
    throw e;
  }
  const posted: { type: string; [k: string]: any }[] = [];
  const i = new Inspector(context as never, store, null as never) as any;
  i.panel = { webview: { postMessage: (m: { type: string }) => posted.push(m) } };
  const settle = () => new Promise((resolve) => setTimeout(resolve, 20));
  const lists = () => posted.filter((m) => m.type === "requests").at(-1)?.saved.map((r: { name: string }) => r.name);
  const type = async (typed: string, at = path) => {
    const doc = workspace.textDocuments.find((d) => d.uri.path === at);
    if (doc) doc.text = typed;
    else {
      const opened = { uri: Uri.file(at), text: typed, getText: () => opened.text, offsetAt: () => 0, positionAt: () => new Position(0, 0) };
      workspace.textDocuments.push(opened);
    }
    for (const l of edits) l({ document: { uri: Uri.file(at) } });
    await settle();
  };
  const change = async (written: string, at = path) => {
    files.set(at, new TextEncoder().encode(written));
    const relative = at.replace(/^\/ws\//, "");
    for (const w of watchers) {
      const p = w.pattern as string | { pattern: string; base?: string; baseUri?: { path: string } };
      const hit =
        typeof p === "string"
          ? globMatches(p, at) || globMatches(p, relative)
          : globMatches(p.pattern, at.slice(((p.baseUri?.path ?? p.base ?? "").replace(/\/$/, "") + "/").length));
      if (hit) for (const l of w.listeners) l(Uri.file(at));
    }
    await settle();
  };
  return { push: () => i.pushRequests(), lists, type, change, restore };
}

describe("inspector requests file as the editor has it", () => {
  const feed = '{"requests": [{"name": "feed", "url": "/feed"}]}';
  const typed = '{"requests": [{"name": "feed", "url": "/feed"}, {"name": "typed", "url": "/typed"}]}';

  it("lists again what is typed in the open requests file, saved or not", async () => {
    const w = watchedInspector(feed);
    try {
      await w.push();
      assert.deepEqual(w.lists(), ["feed"]);
      await w.type(typed);
      assert.deepEqual(w.lists(), ["feed", "typed"]);
      await w.type('{"requests": [{"name": "typed", "url": "/typed"}]}');
      assert.deepEqual(w.lists(), ["typed"]);
    } finally {
      w.restore();
    }
  });

  it("lists again what is typed in the open requests file the setting names", async () => {
    settings.set("streamlord.inspector.requestsFile", "requests/inspector.json");
    const w = watchedInspector(feed, "/ws/requests/inspector.json");
    try {
      await w.push();
      assert.deepEqual(w.lists(), ["feed"]);
      await w.type(typed);
      assert.deepEqual(w.lists(), ["feed", "typed"]);
    } finally {
      w.restore();
      settings.clear();
    }
  });

  it("lists again when the requests file changes on disk, at the default path and at the path the setting names", async () => {
    const atDefault = watchedInspector(feed);
    try {
      await atDefault.push();
      await atDefault.change(typed);
      assert.deepEqual(atDefault.lists(), ["feed", "typed"]);
    } finally {
      atDefault.restore();
    }
    settings.set("streamlord.inspector.requestsFile", "requests/inspector.json");
    const named = watchedInspector(feed, "/ws/requests/inspector.json");
    try {
      await named.push();
      assert.deepEqual(named.lists(), ["feed"]);
      await named.change(typed);
      assert.deepEqual(named.lists(), ["feed", "typed"]);
    } finally {
      named.restore();
      settings.clear();
    }
  });
});
