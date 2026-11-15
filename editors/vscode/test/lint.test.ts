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

  // Each editor names the file's engine from its own language id, so a file associated with an engine, or untitled, gets its rules.
  it("leaves each engine's own syntax out of the checks, and judges the rest", () => {
    const lint = new Lint(() => {});
    const issues = (language: string, page: string, key = "file:///page.x") => lint.issues(key, page, false, "data-", [], null, null, language).map((i) => i.code);
    const backtick = `<div data-text="\`Hi \${user.name!'x'}\`"></div>`;
    assert.deepEqual(issues("freemarker", backtick), [], "FreeMarker writes ${} inside a JavaScript template");
    assert.deepEqual(issues("freemarker", backtick, "untitled:Untitled-1"), [], "an untitled FreeMarker file");
    assert.deepEqual(issues("html", backtick), ["expression-syntax"], "JavaScript's own in an HTML file");
    assert.deepEqual(issues("freemarker", `<div data-text="<#if a>$a<#else>$b</#if>"></div>`), [], "a FreeMarker directive");
    assert.deepEqual(issues("php", `<div data-text="<?= htmlspecialchars("a") ?>"></div>`), [], "PHP");
    assert.deepEqual(issues("razor", `<div data-on:click="@@post('/save')" data-text="@Model.Name" data-show="@(Model.A + 1)"></div>`), [], "Razor");
    assert.deepEqual(issues("razor", `<input data-bind:@Model.Key="x"><div data-signals:@(Model.K)="1"></div>`), [], "Razor in a name");
    // Razor writes @@ as the @ Datastar reads: the rest is judged.
    assert.deepEqual(issues("razor", `<div data-on:click="@@post('/save'"></div>`), ["expression-syntax"], "Razor's @@post( is Datastar's @post(");
    // Only the engine's placeholder is left out: what is around it is still judged.
    assert.deepEqual(issues("jte", "<div data-text=\"`Hi ${name}` + ((\"></div>"), ["expression-syntax"], "the rest of a jte value");
  });
});
