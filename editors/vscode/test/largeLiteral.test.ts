import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeKotlin } from "../src/analyze.ts";
import { applyFix } from "../src/expression.ts";

/**
 * One literal with thousands of holes is judged in time that grows with the literal, not with its
 * square: a fix that rewrites the whole literal is made only when it is applied, and once for all
 * the holes that offer it. LargeLiteralTest.kt holds the analysis to the same.
 */
describe("a literal with thousands of holes", () => {
  it("is judged in a few seconds at most, its fixes made only when applied", () => {
    let rows = "";
    for (let i = 0; i < 1000; i++) rows += `<p title="$q" data-text="$k" class="\${q.trim()}">$q</p>\n`;
    const src = `fun f(q: String, k: Int): String {\n    // language=HTML\n    return """<div>${rows}</div>"""\n}\n`;
    const started = performance.now();
    const issues = analyzeKotlin(src, { prefix: "data-", checkHtmlAttributes: true, constants: new Set() });
    const ms = performance.now() - started;
    assert.equal(issues.filter((i) => i.code === "unescaped-interpolation").length, 3000);
    assert.equal(issues.filter((i) => i.code === "kotlin-interpolation").length, 1000);
    assert.ok(ms < 10_000, `took ${Math.round(ms)} ms`);
    const trap = issues.find((i) => i.code === "kotlin-interpolation");
    const dollars = trap?.fixes?.find((f) => f.title.startsWith("Make it"));
    assert.ok(dollars && applyFix(src, dollars).includes(`$$"""<div><p title="$$q" data-text="$k" class="$\${q.trim()}">$$q</p>\n<p title="$$q" data-text="$$k"`));
  });
});
