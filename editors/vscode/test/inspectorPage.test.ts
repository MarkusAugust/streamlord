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
});
