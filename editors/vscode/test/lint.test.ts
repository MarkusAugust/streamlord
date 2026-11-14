import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { Lint } from "../src/lint.ts";

describe("what the analysis finds in the open documents", () => {
  // Templates nested deeper than the stack: the analysis throws a RangeError on it.
  const tooDeep = 'val a = "' + '${"'.repeat(20_000) + "x" + '"}'.repeat(20_000) + '"';
  const flawed = 'val b = div { attributes["data-on:click"] = "@Post(\'/x\')" }';

  it("gives a document the analysis throws on no diagnostics and one line in the log, and reads the others", () => {
    const lines: string[] = [];
    const lint = new Lint((line) => lines.push(line));
    assert.deepEqual(lint.issues("file:///deep.kt", tooDeep, true, "data-", [], null, null), []);
    assert.equal(lint.issues("file:///flawed.kt", flawed, true, "data-", [], null, null).length, 1);
    assert.equal(lines.length, 1);
    assert.match(lines[0]!, /file:\/\/\/deep\.kt/);
    assert.match(lines[0]!, /Maximum call stack size exceeded/);
  });

  it("says it once while the document stays as it is, and again after it has been read", () => {
    const lines: string[] = [];
    const lint = new Lint((line) => lines.push(line));
    lint.issues("file:///deep.kt", tooDeep, true, "data-", [], null, null);
    lint.issues("file:///deep.kt", tooDeep + " ", true, "data-", [], null, null);
    assert.equal(lines.length, 1);
    lint.issues("file:///deep.kt", flawed, true, "data-", [], null, null);
    lint.issues("file:///deep.kt", tooDeep, true, "data-", [], null, null);
    assert.equal(lines.length, 2);
  });

  // jte, kte, FreeMarker and Velocity write ${...} before the browser reads the value, inside a JavaScript template too.
  it("leaves an engine's dollar braces in a JavaScript template to the engine", () => {
    const lint = new Lint(() => {});
    const page = `<div data-text="\`Hi \${user.name!'x'}\`"></div>`;
    assert.deepEqual(lint.issues("file:///page.ftl", page, false, "data-", [], null, null), []);
    assert.equal(lint.issues("file:///page.html", page, false, "data-", [], null, null).length, 1, "JavaScript's own in an HTML file");
  });
});
