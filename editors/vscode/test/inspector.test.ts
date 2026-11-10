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

/** Where the class that opens at `start` ends, or -1 when it is no class; a `]` first in it is one of its characters. */
function classEnd(glob: string, start: number): number {
  let j = start + 1;
  if (glob[j] === "!" || glob[j] === "^") j++;
  return glob.indexOf("]", j + 1);
}

/** A glob as VS Code reads it: `**` is any folders, `*` any part of a name, `?` one character, `[...]` a class and `{a,b}` either. */
function globMatches(glob: string, path: string): boolean {
  let re = "";
  let braces = 0;
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
    else if (c === "[" && classEnd(glob, i) > 0) {
      // `!` or `^` first takes the class's complement.
      const end = classEnd(glob, i);
      const negated = glob[i + 1] === "!" || glob[i + 1] === "^";
      const members = glob.slice(i + (negated ? 2 : 1), end).replace(/[\\\]^]/g, "\\$&");
      re += `[${negated ? "^" : ""}${members}]`;
      i = end;
    } else if (c === "{") {
      braces++;
      re += "(?:";
    } else if (c === "}" && braces > 0) {
      braces--;
      re += ")";
    } else if (c === "," && braces > 0) re += "|";
    else re += c.replace(/[.+^$()|[\]{}\\]/g, "\\$&");
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
  const watchers: { pattern: unknown; listeners: ((u: unknown) => void)[]; disposed: boolean }[] = [];
  const configurations: ((e: { affectsConfiguration: (section: string) => boolean }) => void)[] = [];
  const { onDidChangeTextDocument, createFileSystemWatcher, onDidChangeConfiguration } = workspace;
  (workspace as Record<string, unknown>).onDidChangeConfiguration = (l: (e: { affectsConfiguration: (section: string) => boolean }) => void) => {
    configurations.push(l);
    return { dispose: () => {} };
  };
  (workspace as Record<string, unknown>).onDidChangeTextDocument = (l: (e: { document: { uri: unknown } }) => void) => {
    edits.push(l);
    return { dispose: () => {} };
  };
  (workspace as Record<string, unknown>).createFileSystemWatcher = (pattern: unknown) => {
    const w = { pattern, listeners: [] as ((u: unknown) => void)[], disposed: false };
    watchers.push(w);
    const on = (l: (u: unknown) => void) => {
      w.listeners.push(l);
      return { dispose: () => {} };
    };
    return { onDidChange: on, onDidCreate: on, onDidDelete: on, dispose: () => (w.disposed = true) };
  };
  const restore = () => {
    workspace.onDidChangeTextDocument = onDidChangeTextDocument;
    workspace.createFileSystemWatcher = createFileSystemWatcher;
    workspace.onDidChangeConfiguration = onDidChangeConfiguration;
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
    for (const w of watchers.filter((x) => !x.disposed)) {
      const p = w.pattern as string | { pattern: string; base?: string; baseUri?: { path: string } };
      const base = typeof p === "string" ? "" : `${(p.baseUri?.path ?? p.base ?? "").replace(/\/$/, "")}/`;
      const hit = typeof p === "string" ? globMatches(p, at) || globMatches(p, relative) : at.startsWith(base) && globMatches(p.pattern, at.slice(base.length));
      if (hit) for (const l of w.listeners) l(Uri.file(at));
    }
    await settle();
  };
  /** Set a setting, and tell the store as VS Code does: every section that holds the key is affected. */
  const configure = async (key: string, value: unknown) => {
    settings.set(key, value);
    for (const l of configurations) l({ affectsConfiguration: (section: string) => key === section || key.startsWith(`${section}.`) });
    await settle();
  };
  return { push: () => i.pushRequests(), lists, type, change, configure, restore };
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

  /** What the list of a requests file at `file` misses, the setting being `setting`: as read, as typed, and as changed on disk. */
  async function followed(setting: string, file: string): Promise<string[]> {
    const found: string[] = [];
    settings.set("streamlord.inspector.requestsFile", setting);
    const w = watchedInspector(feed, file);
    try {
      await w.push();
      if (JSON.stringify(w.lists()) !== '["feed"]') found.push(`${setting} reads ${JSON.stringify(w.lists())}`);
      await w.change(typed);
      if (JSON.stringify(w.lists()) !== '["feed","typed"]') found.push(`${setting} after a change on disk lists ${JSON.stringify(w.lists())}`);
      await w.type(feed);
      if (JSON.stringify(w.lists()) !== '["feed"]') found.push(`${setting} as typed lists ${JSON.stringify(w.lists())}`);
    } finally {
      w.restore();
      settings.clear();
    }
    return found;
  }

  it("follows the requests file the setting names however its path is written", async () => {
    const found = [...(await followed("./requests/inspector.json", "/ws/requests/inspector.json")), ...(await followed("requests//inspector.json", "/ws/requests/inspector.json"))];
    assert.equal(found.join("\n"), "");
  });

  it("follows a requests file whose path holds glob characters as that very path", async () => {
    const found = [...(await followed("req[1]/inspector.json", "/ws/req[1]/inspector.json")), ...(await followed("a{b}/inspector.json", "/ws/a{b}/inspector.json"))];
    assert.equal(found.join("\n"), "");
  });

  it("follows the requests file a setting changed after the start names, on disk", async () => {
    const w = watchedInspector(feed, "/ws/requests/inspector.json");
    try {
      await w.push();
      assert.deepEqual(w.lists(), []);
      await w.configure("streamlord.inspector.requestsFile", "requests/inspector.json");
      assert.deepEqual(w.lists(), ["feed"]);
      await w.change(typed);
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
