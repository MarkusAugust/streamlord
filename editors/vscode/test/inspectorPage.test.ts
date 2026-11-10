import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

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
    addEventListener() {}
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
    const send = (requestProblems: string[], variableErrors: string[] = []) =>
      listener({ data: { type: "requests", saved: [], recent: [], variables: {}, newLabel: "New request…", variablesText: "", variableErrors, requestProblems } });
    return { send, problems: byId.get("requestProblems"), variableErrors: byId.get("varErrors")! };
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
});
