import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeHtml } from "../src/analyze.ts";
import { tokenize } from "../src/markup.ts";
import { readFileSync } from "node:fs";

// How long the markup takes to read, and where a comment ends; what it reports is in the shared corpus.
const opts = { prefix: "data-", checkHtmlAttributes: true };

describe("time", () => {
  // Each of these took seconds, or minutes, with the form it replaces and takes milliseconds now:
  // the limit leaves a slow machine a wide margin.
  const within = (ms: number, f: () => unknown) => {
    const start = performance.now();
    f();
    const took = performance.now() - start;
    assert.ok(took < ms, `took ${took.toFixed(0)} ms`);
  };

  it("reads a filter string full of escapes without backtracking", () => {
    within(1000, () => analyzeHtml(`<pre data-json-signals="{include: '/${"\\\\".repeat(19)}x'}"></pre>`, opts));
    within(1000, () => analyzeHtml(`<pre data-json-signals='{"include": "/^${Array.from({ length: 16 }, (_, i) => `a${i}`).join("\\\\.")}"}'></pre>`, opts));
  });

  it("reads a line of slashes that never close in linear time", () => {
    within(1000, () => analyzeHtml(`<div data-text="${"(/[".repeat(20000)}"></div>`, opts));
  });

  it("tells each signal in a string the client pairs otherwise in linear time", () => {
    // 60 KB of rewrites inside one string, each told which quote opens it.
    within(2000, () => analyzeHtml(`<div data-text="/'/.test('x') + '${"$a ".repeat(20000)}'"></div>`, opts));
  });
});

describe("comments", () => {
  it("end where the HTML tokenizer ends them, conformance/dom/comment-ends.json", () => {
    const cases = JSON.parse(readFileSync(new URL("../../../conformance/dom/comment-ends.json", import.meta.url), "utf8")) as { input: string; element: boolean }[];
    assert.equal(cases.length, 16);
    for (const c of cases) assert.equal(tokenize(c.input).tags.some((t) => t.name === "b" && !t.closing), c.element, c.input);
  });
});
