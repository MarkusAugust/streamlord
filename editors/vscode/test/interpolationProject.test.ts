import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { analyzeKotlin } from "../src/analyze.ts";
import { constantNames, otherDeclaredNames, projectConstants } from "../src/interpolation.ts";

/**
 * The files of the interpolation test project, and what the analysis must say about each
 * hole in them; InterpolationProjectTest.kt reads the same expected.json.
 */
const dir = new URL("./fixtures/interpolationtests/", import.meta.url);
const expected = JSON.parse(readFileSync(new URL("expected.json", dir), "utf8")) as {
  constants: string[];
  files: Record<string, { start: number; end: number; code: string; severity: string; message: string }[]>;
};
const CODES = new Set(["kotlin-interpolation", "unescaped-interpolation", "unsafe-interpolation", "interpolate-template", "interpolate-holes"]);

describe("the interpolation test project", () => {
  it("finds the constants the workspace index gives the analysis", () => {
    const names = projectConstants(Object.keys(expected.files).map((f) => readFileSync(new URL(f, dir), "utf8")));
    assert.deepEqual([...names].sort(), [...expected.constants].sort());
  });

  for (const [file, issues] of Object.entries(expected.files)) {
    it(file, () => {
      const source = readFileSync(new URL(file, dir), "utf8");
      const all = analyzeKotlin(source, { prefix: "data-", checkHtmlAttributes: true, constants: new Set(expected.constants) });
      const seen = all
        .filter((i) => CODES.has(i.code ?? ""))
        .sort((a, b) => a.start - b.start || a.end - b.end || (a.code ?? "").localeCompare(b.code ?? ""))
        .map((i) => ({ start: i.start, end: i.end, code: i.code, severity: i.severity, message: i.message }));
      assert.deepEqual(seen, issues.map(({ start, end, code, severity, message }) => ({ start, end, code, severity, message })));
      assert.deepEqual(all.filter((i) => i.code === "missing-id" || i.code === "top-level-text"), []);
    });
  }
});

describe("the constants of a project", () => {
  const constants = [
    `const val title = "Home"`,
    `internal const val label = "Name"`,
    `private const val secret = "s"`,
    `object Keys { const val KEY = "k"; private const val HIDDEN = "h" }`,
    `private object Quiet { const val inner = "i" }`,
    `val text = "const val fake = 1"`,
  ].join("\n");

  it("collects a const val other files can see, internal too, and no private one", () => {
    assert.deepEqual([...constantNames(constants)].sort(), ["KEY", "label", "title"]);
  });

  it("collects every other name another file could mean by a bare name", () => {
    const src = [
      `val title get() = request.queryParameters["title"]`,
      `class Page(val heading: String, private val hidden: String, plain: String) {`,
      `    var count = 0`,
      `    private val own = 1`,
      `    fun render(param: String) { val inside = 1 }`,
      `}`,
      `fun page(query: String) = query`,
      `private val quiet = 1`,
      `internal var shared = 2`,
    ].join("\n");
    assert.deepEqual([...otherDeclaredNames(src)].sort(), ["count", "heading", "shared", "title"]);
  });

  it("drops a constant that another file declares as something else", () => {
    const a = `const val title = "Home"\nconst val label = "Name"`;
    const b = `class Request { val title get() = header("title") }`;
    assert.deepEqual([...projectConstants([a, b])].sort(), ["label"]);
  });

  it("keeps a constant that another file only takes as a parameter", () => {
    const a = `const val title = "Home"`;
    const b = `fun page(title: String) = title\nfun list() { val title = 3 }`;
    assert.deepEqual([...projectConstants([a, b])], ["title"]);
  });

  it("warns on a hole that names a private constant in one file and a getter in another", () => {
    const a = `private const val title = "Home"`;
    const b = `val title get() = call.request.queryParameters["title"]`;
    const c = `import org.intellij.lang.annotations.Language\n\n@Language("html")\nfun page() = """<h1>$title</h1>"""`;
    const issues = analyzeKotlin(c, { prefix: "data-", checkHtmlAttributes: true, constants: projectConstants([a, b, c]) });
    assert.ok(issues.some((i) => i.code === "unescaped-interpolation"), JSON.stringify(issues));
    const silent = analyzeKotlin(c, { prefix: "data-", checkHtmlAttributes: true, constants: projectConstants([`const val title = "Home"`, c]) });
    assert.deepEqual(silent.filter((i) => i.code === "unescaped-interpolation"), []);
  });
});
