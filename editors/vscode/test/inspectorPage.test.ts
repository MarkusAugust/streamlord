import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mergeVariables, parseEnv, variableValues, type SavedRequest } from "../src/requests.ts";
import { twinCases } from "./twins.ts";
import { createServer } from "node:net";
import { createServer as createHttpServer } from "node:http";
import type { AddressInfo } from "node:net";
import { Inspector } from "../src/inspector.ts";
import { RequestStore } from "../src/requestStore.ts";
import { RunningServers } from "../src/serverLog.ts";
import type { DatastarFrame } from "../src/sse.ts";
import { files, Position, settings, unreadable, Uri, window, workspace, type OpenDocument } from "./vscode-mock.ts";

/**
 * The inspector's page is a template literal, so its script escapes are TypeScript's and the
 * typecheck never sees the script: a `\n` written once ends a string on a new line. Render the
 * template as the extension does and parse the script it gives.
 */
describe("inspector page", () => {
  const src = readFileSync(new URL("../src/inspector.ts", import.meta.url), "utf8");
  const open = "return /* html */ `";
  const body = src.slice(src.indexOf(open) + open.length, src.lastIndexOf("</html>`;") + "</html>".length);
  const page = new Function("cspSource", `return \`${body}\`;`)("vscode-resource:") as string;
  const script = page.slice(page.indexOf("<script>") + "<script>".length, page.lastIndexOf("</script>"));

  it("has a script that parses", () => {
    assert.doesNotThrow(() => new Function("acquireVsCodeApi", "document", "window", script));
  });

  it("edits variables, and no longer opens the requests file", () => {
    assert.ok(page.includes('id="editVars"'));
    assert.ok(!page.includes('id="openFile"'));
    assert.ok(!page.includes("edit file"));
  });

  /** Just enough of an element for the page's script: what it holds, and whether markup was ever written into it. */
  class Element {
    children: Element[] = [];
    className = "";
    value = "";
    markup: string[] = [];
    private text = "";
    get textContent(): string {
      return this.text + this.children.map((c) => c.textContent).join("");
    }
    set textContent(v: string) {
      this.children = [];
      this.text = v;
    }
    set innerHTML(v: string) {
      this.children = [];
      this.text = "";
      if (v !== "") this.markup.push(v);
    }
    append(...nodes: Element[]) {
      this.children.push(...nodes);
    }
    appendChild(node: Element) {
      this.children.push(node);
    }
    prepend(...nodes: Element[]) {
      this.children.unshift(...nodes);
    }
    replaceChildren(...nodes: Element[]) {
      this.children = nodes;
    }
    /** What the script wrote to `hidden`, to an attribute or to `style`, in order. */
    written: string[] = [];
    readonly style: Record<string, string> = new Proxy({} as Record<string, string>, {
      set: (target, key, value) => {
        this.written.push(`style.${String(key)}=${value}`);
        target[String(key)] = value;
        return true;
      },
    });
    set hidden(v: boolean) {
      this.written.push(`hidden=${v}`);
    }
    setAttribute(name: string, value: string) {
      this.written.push(`@${name}=${value}`);
    }
    removeAttribute(name: string) {
      this.written.push(`@${name} removed`);
    }
    toggleAttribute(name: string) {
      this.written.push(`@${name} toggled`);
    }
    private readonly listeners: [string, (e: unknown) => void][] = [];
    addEventListener(type: string, listener: (e: unknown) => void) {
      this.listeners.push([type, listener]);
    }
    /** Type `value` into the field, as a user does. */
    type(value: string) {
      this.value = value;
      for (const [type, listener] of this.listeners) if (type === "input") listener({});
    }
    querySelector() {
      return null;
    }
  }

  /** The page running over stand-in elements, one per id it declares; `send` is a message from the extension. */
  function running() {
    const byId = new Map([...page.matchAll(/\bid="([^"]+)"/g)].map((m) => [m[1], new Element()]));
    const document = { getElementById: (id: string) => byId.get(id) ?? null, createElement: () => new Element(), body: new Element() };
    let listener: (ev: { data: unknown }) => void = () => {};
    const window = { addEventListener: (_type: string, l: typeof listener) => (listener = l), scrollX: 0, scrollY: 0 };
    new Function("acquireVsCodeApi", "document", "window", script)(() => ({ postMessage() {} }), document, window);
    const requests = (more: Record<string, unknown>) =>
      listener({ data: { type: "requests", saved: [], recent: [], variables: {}, newLabel: "New request…", variablesText: "", variableErrors: [], requestProblems: [], ...more } });
    const send = (requestProblems: string[], variableErrors: string[] = []) => requests({ requestProblems, variableErrors });
    const post = (message: unknown) => listener({ data: message });
    return { send, requests, post, element: (id: string) => byId.get(id)!, problems: byId.get("requestProblems"), variableErrors: byId.get("varErrors")! };
  }

  it("has a place for what is wrong with the requests file, right after the row of the request list", () => {
    const row = page.indexOf('<div class="saved-row">');
    const select = page.indexOf('<select id="saved">');
    const end = page.indexOf("</div>", row) + "</div>".length;
    assert.ok(row >= 0 && select > row && select < end);
    assert.match(page.slice(end, end + 80), /^\s*<[a-z]+\b[^>]*\bid="requestProblems"/);
  });

  it("shows each error of the variables file as an error line", () => {
    const { send, variableErrors } = running();
    send([], ["one", "two"]);
    assert.deepEqual(variableErrors.children.map((p) => [p.className, p.textContent]), [["error", "one"], ["error", "two"]]);
    send([], []);
    assert.equal(variableErrors.children.length, 0);
  });

  it("shows each problem of the requests file as an error line", () => {
    const { send, problems } = running();
    assert.ok(problems, "the page has no #requestProblems");
    const skipped = ["Request 1 in .streamlord/inspector.json is not read: it is not an object.", 'Request 3 in .streamlord/inspector.json is not read: it has no "url".'];
    send(skipped);
    assert.deepEqual(problems.children.map((p) => [p.className, p.textContent]), skipped.map((m) => ["error", m]));
  });

  it("shows why a file is not a requests file", () => {
    const { send, problems } = running();
    assert.ok(problems, "the page has no #requestProblems");
    const error = '.streamlord/inspector.json is not a requests file: it must be an object with a "requests" list.';
    send([error]);
    assert.deepEqual(problems.children.map((p) => [p.className, p.textContent]), [["error", error]]);
  });

  it("shows nothing about a requests file without problems", () => {
    const { send, problems } = running();
    assert.ok(problems, "the page has no #requestProblems");
    assert.equal(problems.children.length, 0);
    send([]);
    assert.equal(problems.children.length, 0);
    assert.equal(problems.textContent, "");
  });

  it("takes the problems away once the file is put right, and does not pile them up", () => {
    const { send, problems } = running();
    assert.ok(problems, "the page has no #requestProblems");
    send(["one", "two"]);
    send(["two"]);
    assert.deepEqual(problems.children.map((p) => p.textContent), ["two"]);
    send([]);
    assert.equal(problems.children.length, 0);
    assert.equal(problems.textContent, "");
  });

  // The file is shared, so a name in it is someone else's text.
  it("shows a problem that holds markup as text", () => {
    const { send, problems } = running();
    assert.ok(problems, "the page has no #requestProblems");
    const message = "Request 1 in <b>x</b><img src=x onerror=alert(1)>.json is not read: it is not an object.";
    send([message]);
    assert.deepEqual(problems.children.map((p) => p.textContent), [message]);
    assert.deepEqual([problems.markup, ...problems.children.map((p) => p.markup)], [[], []]);
  });

  // The page fills the preview itself as the URL is typed, with the variables it was sent; the
  // cases are those of previewUrl, which the extension and the IntelliJ plugin hold.
  describe("shows under the URL what previewUrl gives", () => {
    type Preview = { name: string; call: string; url: string; env: string; defaultUrl: string; running?: { url: string; name: string }; expected: string };
    const cases = twinCases<Preview>("requests.json").filter((c) => c.call === "previewUrl");
    it("has cases to show", () => assert.ok(cases.length > 0));
    for (const c of cases) {
      it(c.name, () => {
        const { requests, element } = running();
        requests({ variables: variableValues(mergeVariables(c.defaultUrl, parseEnv(c.env), c.running ?? null)) });
        element("url").type(c.url);
        assert.equal(element("resolved").textContent, c.expected);
      });
    }
  });

  it("follows the variables when they change under a URL that is already typed", () => {
    const { requests, element } = running();
    const variables = (id: string) => variableValues(mergeVariables("http://localhost:8080/", parseEnv(JSON.stringify({ params: { id } })), null));
    requests({ variables: variables("4 2/x"), current: { url: "{{baseUrl}}/feed/{{id}}" } });
    assert.equal(element("resolved").textContent, "http://localhost:8080/feed/4%202%2Fx");
    requests({ variables: variables("7") });
    assert.equal(element("resolved").textContent, "http://localhost:8080/feed/7");
  });

  // Hidden with `hidden` or `display` the marker leaves the row, and Save and Delete move under the pointer.
  describe("the modified marker", () => {
    const tag = /<[a-z]+\b[^>]*\bid="dirty"[^>]*>/.exec(page)?.[0] ?? "";
    const css = page.slice(page.indexOf("<style>"), page.indexOf("</style>"));
    const rules = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)].filter((r) => /(#dirty|\.dirty)\b/.test(r[1]!)).map((r) => r[2]!);
    const feed: SavedRequest = { name: "feed", url: "/feed", method: "GET", signals: "", headers: "" };

    it("starts out of sight and in its place: hidden by visibility, never by the hidden attribute or display", () => {
      assert.ok(tag, "the page has no #dirty");
      assert.doesNotMatch(tag.replace(/"[^"]*"/g, '""'), /\shidden\b/);
      assert.ok(rules.every((r) => !/\bdisplay\s*:/.test(r)), rules.join(" "));
      assert.ok(/visibility\s*:\s*hidden/.test(tag) || rules.some((r) => /visibility\s*:\s*hidden/.test(r)), `${tag} ${rules.join(" ")}`);
    });

    it("is shown and hidden by visibility alone as a loaded request is edited and put back", () => {
      const { requests, element } = running();
      const dirty = element("dirty");
      assert.notEqual(dirty.style.visibility, "visible");
      requests({ saved: [feed], current: feed });
      assert.notEqual(dirty.style.visibility, "visible");
      element("url").type("/feed/2");
      assert.equal(dirty.style.visibility, "visible");
      element("url").type("/feed");
      assert.equal(dirty.style.visibility, "hidden");
      element("signals").type('{"a": 1}');
      assert.equal(dirty.style.visibility, "visible");
      assert.deepEqual(dirty.written.filter((w) => !w.startsWith("style.visibility=")), []);
    });

    it("stays out of sight for a request that is not a saved one", () => {
      const { requests, element } = running();
      requests({ saved: [feed], current: { url: "/new" } });
      element("url").type("/newer");
      assert.notEqual(element("dirty").style.visibility, "visible");
      assert.deepEqual(element("dirty").written.filter((w) => !w.startsWith("style.visibility=")), []);
    });
  });
  /** The page over the extension: what the extension posts is what the page is sent. `i` is the inspector, whose handle and applyFrame are private. */
  function overExtension() {
    files.clear();
    unreadable.clear();
    settings.clear();
    workspace.textDocuments.length = 0;
    workspace.workspaceFolders = [{ uri: Uri.file("/ws") }];
    const shown = running();
    const context = { subscriptions: [] as unknown[], workspaceState: { get: <T>(_k: string, d: T) => d, update: async () => {} } };
    const servers = new RunningServers();
    const i = new Inspector(context as never, new RequestStore(context as never, servers), servers) as any;
    // While `held` is set, the status messages wait there instead of reaching the page.
    const hold = { held: null as unknown[] | null };
    i.panel = {
      webview: {
        postMessage: async (m: { type?: string }) => {
          if (hold.held !== null && m.type === "status") hold.held.push(m);
          else shown.post(m);
          return true;
        },
      },
    };
    const status = shown.element("status");
    const env = (text: string) => files.set("/ws/.streamlord/env.json", new TextEncoder().encode(text));
    const request = (url: string, more: Partial<SavedRequest> = {}): SavedRequest => ({ name: "", url, method: "GET", signals: "", headers: "", ...more });
    return { ...shown, i, status, env, request, hold };
  }

  /** A port nothing listens on. */
  async function closedPort(): Promise<number> {
    const server = createServer();
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const { port } = server.address() as AddressInfo;
    await new Promise((resolve) => server.close(resolve));
    return port;
  }

  describe("the status belongs to the request it is about", () => {
    it("says error, with the URL of the request in its title, when a request cannot be sent or fails", async () => {
      const port = await closedPort();
      const cases: { what: string; env: string; request: (p: ReturnType<typeof overExtension>) => SavedRequest; url: string; says: string }[] = [
        { what: "an env file that is not valid", env: "[]", request: (p) => p.request("{{baseUrl}}/x?a=1&b=2"), url: "{{baseUrl}}/x?a=1&b=2", says: "env.json is not valid" },
        { what: "a variable that is unknown", env: '{"baseUrl": "http://h:1"}', request: (p) => p.request("{{nope}}/x"), url: "{{nope}}/x", says: "{{nope}} is not set" },
        { what: "a variable that is misplaced", env: '{"baseUrl": "http://h:1"}', request: (p) => p.request("{{baseUrl}}/x", { signals: "{{baseUrl}}" }), url: "{{baseUrl}}/x", says: "belongs in the URL field" },
        { what: "a variable that is not set", env: '{"baseUrl": "http://h:1", "params": {"id": ""}}', request: (p) => p.request("{{baseUrl}}/x/{{id}}"), url: "{{baseUrl}}/x/{{id}}", says: "{{id}} is empty" },
        { what: "signals that are not valid JSON", env: '{"baseUrl": "http://h:1"}', request: (p) => p.request("{{baseUrl}}/x", { signals: "{" }), url: "http://h:1/x", says: "Signals are not valid JSON" },
        { what: "a URL that cannot be sent", env: '{"baseUrl": "http://h:1"}', request: (p) => p.request("{{baseUrl}}/x?filter[name]=1"), url: "http://h:1/x?filter[name]=1", says: "The URL holds [" },
        { what: "a header the HTTP client will not set", env: `{"baseUrl": "http://127.0.0.1:${port}"}`, request: (p) => p.request("{{baseUrl}}/x", { headers: "Expect: 100-continue" }), url: `http://127.0.0.1:${port}/x`, says: "expect header" },
        { what: "a failed connection", env: `{"baseUrl": "http://127.0.0.1:${port}"}`, request: (p) => p.request("{{baseUrl}}/x"), url: `http://127.0.0.1:${port}/x`, says: "Connection refused" },
      ];
      const found: string[] = [];
      for (const c of cases) {
        const p = overExtension();
        p.env(c.env);
        p.post({ type: "status", status: "open", http: "200 OK", resolvedUrl: "http://h/old" });
        await p.i.handle({ type: "connect", request: c.request(p) });
        const shown = `${p.status.textContent} | ${(p.status as unknown as { title?: string }).title}`;
        if (shown !== `error | ${c.url}`) found.push(`${c.what}: ${shown}, not error | ${c.url}`);
        const line = p.element("frames").children.find((e) => e.className === "error")?.textContent ?? "";
        if (!line.includes(c.says)) found.push(`${c.what}: the error line says ${line}`);
      }
      assert.equal(found.join("\n"), "");
    });

    it("leaves the status of an open stream as it was when an error is not about the request", async () => {
      const { showInputBox, showWarningMessage } = window as Record<string, unknown>;
      const found: string[] = [];
      try {
        (window as Record<string, unknown>).showInputBox = async () => "a";
        (window as Record<string, unknown>).showWarningMessage = async () => "Delete";
        const failures: { what: string; run: (p: ReturnType<typeof overExtension>) => Promise<void> }[] = [
          {
            what: "a Save that fails",
            run: async (p) => {
              settings.set("streamlord.inspector.requestsFile", "../elsewhere/requests.json");
              await p.i.handle({ type: "save", request: p.request("/a") });
            },
          },
          {
            what: "a Delete that fails",
            run: async (p) => {
              settings.set("streamlord.inspector.requestsFile", "../elsewhere/requests.json");
              await p.i.handle({ type: "delete", name: "feed" });
            },
          },
          { what: "Copy as curl with a variable not set", run: (p) => p.i.handle({ type: "curl", request: p.request("{{nope}}/x") }) },
          {
            what: "Edit variables that fails",
            run: async (p) => {
              workspace.workspaceFolders = undefined;
              await p.i.handle({ type: "defineVariables", names: [] });
            },
          },
          {
            what: "Use baseUrl that fails",
            run: async (p) => {
              workspace.workspaceFolders = undefined;
              await p.i.handle({ type: "useBaseUrl", url: "http://localhost:9" });
            },
          },
          {
            what: "a signal patch the signal store cannot take",
            run: async (p) => {
              const frame = (signals: string): DatastarFrame => ({ event: "datastar-patch-signals", id: null, retry: null, args: { signals }, raw: "", receivedAt: 0 });
              p.i.applyFrame(frame('{"a":[1,2]}'));
              p.i.applyFrame(frame('{"a":{"length":-1}}'));
            },
          },
        ];
        for (const f of failures) {
          const p = overExtension();
          p.post({ type: "status", status: "open", http: "200 OK", resolvedUrl: "http://h/feed" });
          const lines = p.element("frames").children.length;
          await f.run(p);
          settings.clear();
          const errors = p.element("frames").children.slice(0, p.element("frames").children.length - lines).filter((c) => c.className === "error");
          if (errors.length !== 1) found.push(`${f.what} shows ${errors.length} error lines`);
          const shown = `${p.status.textContent} | ${(p.status as unknown as { title?: string }).title}`;
          if (shown !== "open · 200 OK | http://h/feed") found.push(`${f.what}: ${shown}`);
        }
      } finally {
        Object.assign(window, { showInputBox, showWarningMessage });
        settings.clear();
      }
      assert.equal(found.join("\n"), "");
    });
      /** A server that opens a stream on each request and holds it open until it is closed. */
    async function streaming() {
      const server = createHttpServer((_q, res) => {
        res.writeHead(200, { "Content-Type": "text/event-stream" });
        res.write(": open\n\n");
      });
      await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
      const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
      const close = () => {
        server.closeAllConnections();
        return new Promise((resolve) => server.close(resolve));
      };
      return { base, close };
    }

    const statusOf = (p: ReturnType<typeof overExtension>) => `${p.status.textContent} | ${(p.status as unknown as { title?: string }).title}`;

    /** Connect to `base` and wait until the stream is open; `earlier` ends when it does. */
    async function opened(p: ReturnType<typeof overExtension>, base: string): Promise<{ earlier: Promise<void> }> {
      p.env(`{"baseUrl": "${base}"}`);
      const earlier: Promise<void> = p.i.handle({ type: "connect", request: p.request("{{baseUrl}}/feed") });
      const deadline = Date.now() + 5000;
      while (!p.status.textContent.startsWith("open") && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 5));
      assert.equal(statusOf(p), `open · 200 OK · text/event-stream | ${base}/feed`);
      return { earlier };
    }

    it("keeps the status of a new request that failed when the earlier stream's close comes after it", async () => {
      const server = await streaming();
      try {
        const p = overExtension();
        const { earlier } = await opened(p, server.base);
        // The earlier stream's close is held until the new request has failed.
        p.hold.held = [];
        await p.i.handle({ type: "connect", request: p.request("{{nope}}/x") });
        await earlier;
        const held = p.hold.held;
        p.hold.held = null;
        for (const m of held) p.post(m);
        assert.equal(statusOf(p), "error | {{nope}}/x");
      } finally {
        await server.close();
      }
    });

    it("keeps the status Stop gives when the stopped stream's close comes after it", async () => {
      const server = await streaming();
      try {
        const p = overExtension();
        const { earlier } = await opened(p, server.base);
        await p.i.handle({ type: "stop" });
        await earlier;
        assert.equal(statusOf(p), "idle | ");
      } finally {
        await server.close();
      }
    });

    it("shows a change the open editor refuses as an error line naming the file, and leaves the status as it was", async () => {
      const { showInputBox, showWarningMessage, showTextDocument } = window as Record<string, unknown>;
      const applyEdit = workspace.applyEdit;
      const found: string[] = [];
      try {
        Object.assign(window, { showInputBox: async () => "a", showWarningMessage: async () => "Delete", showTextDocument: async () => undefined });
        workspace.applyEdit = async () => false;
        const p = overExtension();
        const doc = (path: string, text: string): OpenDocument => {
          const d: OpenDocument = { uri: Uri.file(path), text, getText: () => d.text, offsetAt: () => 0, positionAt: (o: number) => new Position(0, o) };
          return d;
        };
        const requests = '{"requests": [{"name": "feed", "url": "/feed"}]}';
        files.set("/ws/.streamlord/inspector.json", new TextEncoder().encode(requests));
        p.env('{"baseUrl": "http://h:1"}');
        workspace.textDocuments.push(doc("/ws/.streamlord/inspector.json", requests), doc("/ws/.streamlord/env.json", '{"baseUrl": "http://h:1"}'));
        const changes: [string, string, Record<string, unknown>][] = [
          ["a Save", ".streamlord/inspector.json", { type: "save", request: p.request("/a") }],
          ["a Delete", ".streamlord/inspector.json", { type: "delete", name: "feed" }],
          ["Edit variables", ".streamlord/env.json", { type: "defineVariables", names: ["signals"] }],
          ["Use baseUrl", ".streamlord/env.json", { type: "useBaseUrl", url: "http://localhost:9" }],
        ];
        for (const [what, file, message] of changes) {
          p.post({ type: "status", status: "open", http: "200 OK", resolvedUrl: "http://h/feed" });
          const before = p.element("frames").children.length;
          await p.i.handle(message);
          const errors = p.element("frames").children.slice(0, p.element("frames").children.length - before).filter((c) => c.className === "error");
          if (errors.length !== 1 || !errors[0]!.textContent.includes(file)) found.push(`${what} shows ${JSON.stringify(errors.map((e) => e.textContent))}`);
          if (statusOf(p) !== "open · 200 OK | http://h/feed") found.push(`${what}: ${statusOf(p)}`);
        }
      } finally {
        Object.assign(window, { showInputBox, showWarningMessage, showTextDocument });
        workspace.applyEdit = applyEdit;
      }
      assert.equal(found.join("\n"), "");
    });
  });
});
