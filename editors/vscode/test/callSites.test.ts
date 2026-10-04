import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeKotlin, htmlStringAt } from "../src/analyze.ts";
import type { Fix, Issue } from "../src/expression.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };
const codes = (issues: { code?: string }[]) => issues.map((i) => i.code);
const apply = (src: string, fix: Fix) => src.slice(0, fix.start) + fix.text + src.slice(fix.end);
const fix = (issue: Issue, title: string) => issue.fixes!.find((f) => f.title.startsWith(title))!;

describe("patch call sites", () => {
  it("sees a selector and a mode given by position", () => {
    assert.deepEqual(analyzeKotlin('patchElements("<tr></tr>", "#rows", ElementPatchMode.APPEND)', opts), []);
    assert.deepEqual(codes(analyzeKotlin('patchElements("<li>x</li>", null, ElementPatchMode.APPEND)', opts)), ["mode-needs-selector"]);
  });

  it("sees the arguments given by name", () => {
    assert.deepEqual(codes(analyzeKotlin('patchElements(elements = "<b>x</b>")', opts)), ["missing-id"]);
    assert.deepEqual(analyzeKotlin('patchElements(elements = "<b>x</b>", selector = "#a")', opts), []);
    assert.deepEqual(codes(analyzeKotlin('removeElements(selector = "")', opts)), ["blank-selector"]);
    assert.deepEqual(codes(analyzeKotlin('executeScript(script = "x</script>")', opts)), ["script-close"]);
  });

  it("reads an imported mode constant, and does not guess at a variable", () => {
    assert.deepEqual(codes(analyzeKotlin('patchElements("<li>x</li>", mode = APPEND)', opts)), ["mode-needs-selector"]);
    assert.deepEqual(analyzeKotlin('patchElements("<li>x</li>", mode = someMode)', opts), []);
    assert.deepEqual(codes(analyzeKotlin('patchElements("<li>x</li>", mode = ElementPatchMode.DEFAULT)', opts)), ["missing-id"]);
  });

  it("reads the selector and the mode by position when the markup is a trailing lambda", () => {
    assert.deepEqual(analyzeKotlin('patchElements("#rows") { li { +"x" } }', opts), []);
    assert.deepEqual(analyzeKotlin('stream.patchElements("#feed", ElementPatchMode.APPEND) { li { } }', opts), []);
    assert.deepEqual(codes(analyzeKotlin("patchElements(null, ElementPatchMode.APPEND) { li { } }", opts)), ["mode-needs-selector"]);
    const src = "patchElements(mode = ElementPatchMode.APPEND) { li { } }";
    const issues = analyzeKotlin(src, opts);
    assert.deepEqual(codes(issues), ["mode-needs-selector"]);
    assert.equal(apply(src, fix(issues[0]!, "Add selector")), 'patchElements(selector = "", mode = ElementPatchMode.APPEND) { li { } }');
    assert.equal(htmlStringAt('patchElements("#rows") { li { } }', 16), null);
  });

  it("finds the end of a script whatever comes before it", () => {
    const src = 'executeScript("İİ x</SCRIPT>")';
    const [issue] = analyzeKotlin(src, opts);
    assert.equal(src.slice(issue!.start, issue!.end), "</SCRIPT");
  });
});

describe("quick fixes at call sites", () => {
  it("keeps the escape of a dollar when it renames the signal after it", () => {
    const backslash = 'dataText("\\$a-b")';
    assert.equal(apply(backslash, fix(analyzeKotlin(backslash, opts)[0]!, "Change to")), 'dataText("\\$aB")');
    const idiom = `dataText("\${'$'}a-b")`;
    assert.equal(apply(idiom, fix(analyzeKotlin(idiom, opts)[0]!, "Write")), `dataText("\${'$'}a - b")`);
    const multi = 'dataText($$"$a-b")';
    assert.equal(apply(multi, fix(analyzeKotlin(multi, opts)[0]!, "Change to")), 'dataText($$"$aB")');
  });

  it("keeps a literal dollar in front of a template in the multi-dollar fix", () => {
    const src = 'dataOnClick("$a + $$b")';
    const onA = analyzeKotlin(src, opts).find((i) => src.slice(i.start, i.end) === "$a")!;
    assert.equal(apply(src, fix(onA, "Make it")), 'dataOnClick($$$"$a + $$$$b")');
  });

  it("replaces the trim call together with the literal when it offers a helper", () => {
    const src = 'dataOnClick("""$count++""".trimIndent())';
    assert.equal(apply(src, fix(analyzeKotlin(src, opts)[0]!, "Use")), 'dataOnClick(increment("count"))');
  });
});
