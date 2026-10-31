import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeKotlin } from "../src/analyze.ts";

/**
 * What the corpus cannot hold: the editor's own facts about a hole, and the time a large file
 * takes. HoleJudgementTest.kt holds the Kotlin twin to the same.
 */
const interpolation = (src: string, opts: Parameters<typeof analyzeKotlin>[1]) => analyzeKotlin(src, opts).filter((i) => i.code === "unescaped-interpolation").map((i) => i.severity);

describe("the judgement of holes", () => {
  it("lets the editor's fact win over what the text shows", () => {
    // The text takes both for the author's own: a literal of markup, a workspace constant.
    const src = 'fun f(b: Boolean) = "<p>${if (b) "<b>x</b>" else "<i>y</i>"} $LABEL</p>"';
    const text = { prefix: "data-", checkHtmlAttributes: true, constants: new Set(["LABEL"]) };
    assert.deepEqual(interpolation(src, text), []);
    assert.deepEqual(interpolation(src, { ...text, holeFact: () => "string" as const }), ["warning", "warning"]);
  });

  it("analyses a file of two thousand functions with HTML in a moment", () => {
    const q = '"""';
    let src = "package p\n";
    for (let i = 0; i < 2000; i++) src += `fun f${i}(q: String, k: Int): String {\n    val x = 1\n    // language=HTML\n    return ${q}<div title="$q"><p>\${k}</p><a href="/x/$q">y</a></div>${q}\n}\n`;
    const started = performance.now();
    const issues = analyzeKotlin(src, { prefix: "data-", checkHtmlAttributes: true });
    const ms = performance.now() - started;
    assert.equal(issues.filter((i) => i.code === "unescaped-interpolation" || i.code === "unsafe-interpolation").length, 4000);
    assert.ok(ms < 1500, `took ${Math.round(ms)} ms`);
  });
});
