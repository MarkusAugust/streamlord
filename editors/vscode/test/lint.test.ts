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
    assert.deepEqual(lint.issues("file:///deep.kt", tooDeep, true, "data-", [], null), []);
    assert.equal(lint.issues("file:///flawed.kt", flawed, true, "data-", [], null).length, 1);
    assert.equal(lines.length, 1);
    assert.match(lines[0]!, /file:\/\/\/deep\.kt/);
    assert.match(lines[0]!, /Maximum call stack size exceeded/);
  });

  it("says it once while the document stays as it is, and again after it has been read", () => {
    const lines: string[] = [];
    const lint = new Lint((line) => lines.push(line));
    lint.issues("file:///deep.kt", tooDeep, true, "data-", [], null);
    lint.issues("file:///deep.kt", tooDeep + " ", true, "data-", [], null);
    assert.equal(lines.length, 1);
    lint.issues("file:///deep.kt", flawed, true, "data-", [], null);
    lint.issues("file:///deep.kt", tooDeep, true, "data-", [], null);
    assert.equal(lines.length, 2);
  });
});
