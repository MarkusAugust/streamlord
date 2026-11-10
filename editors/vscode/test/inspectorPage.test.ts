import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mergeVariables, parseEnv, variableValues, type SavedRequest } from "../src/requests.ts";
import { twinCases } from "./twins.ts";

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
    return { send, requests, element: (id: string) => byId.get(id)!, problems: byId.get("requestProblems"), variableErrors: byId.get("varErrors")! };
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
});
