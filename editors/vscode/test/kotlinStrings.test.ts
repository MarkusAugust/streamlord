import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { analyzeKotlin } from "../src/analyze.ts";
import { readKotlinStringAt } from "../src/kotlinStrings.ts";
import { findCallSites } from "../src/scanner.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };
const codes = (issues: { code?: string }[]) => issues.map((i) => i.code);
const sites = (src: string) => findCallSites(src, new Set(["dataOnClick", "dataText"]));

describe("kotlin string reader", () => {
  it("points an end-of-input error in a raw string at its closing quotes", () => {
    const src = `dataOnClick("""@get('/x') +""")`;
    const [issue] = analyzeKotlin(src, opts);
    assert.equal(issue!.code, "expression-syntax");
    assert.equal(issue!.start, src.indexOf('""")'));
    const quoted = `dataOnClick(""""x" +""")`;
    assert.equal(analyzeKotlin(quoted, opts)[0]!.start, quoted.indexOf('""")'));
  });

  it("does not end a template at a brace in a character literal", () => {
    const src = `"\${if (c == '}') 1 else 2} +"`;
    const s = readKotlinStringAt(src, 0)!;
    assert.equal(s.text, "__kt__ +");
    assert.equal(readKotlinStringAt(`"\${x.map { '{' }} +"`, 0)!.text, "__kt__ +");
  });

  it("reads a template whose name is not ascii", () => {
    const s = readKotlinStringAt('"$år + 1"', 0)!;
    assert.deepEqual(s.interpolations.map((i) => [i.kind, i.text]), [["simple", "år"]]);
    assert.equal(s.text, "__kt__ + 1");
  });
});

describe("call site arguments", () => {
  it("takes a literal followed by trimIndent or trimMargin as the string argument", () => {
    assert.ok(sites('dataOnClick("""$count++""".trimIndent())')[0]!.args[0]!.string);
    assert.ok(sites('dataText("""\n  |$a\n""".trimMargin("|"))')[0]!.args[0]!.string);
    assert.equal(sites('dataOnClick("x".trim())')[0]!.args[0]!.string, null);
    assert.equal(sites('dataOnClick("x" + y)')[0]!.args[0]!.string, null);
    assert.deepEqual(codes(analyzeKotlin('dataOnClick("""$count++""".trimIndent())', opts)), ["kotlin-interpolation"]);
    assert.deepEqual(
      codes(analyzeKotlin('patchElements("""<li>x</li>""".trimIndent(), mode = ElementPatchMode.APPEND)', opts)),
      ["mode-needs-selector"],
    );
  });

  it("takes a literal with a comment before or after it as the string argument", () => {
    assert.ok(sites('dataOnClick(/* why */ "x")')[0]!.args[0]!.string);
    assert.ok(sites('dataOnClick(\n  // why\n  "x",\n)')[0]!.args[0]!.string);
    assert.ok(sites('dataText("x" /* after */)')[0]!.args[0]!.string);
    assert.equal(sites('dataText(\n  "x",\n  // trailing\n)')[0]!.args.length, 1);
  });
});
