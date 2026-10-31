import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { analyzeHtml, analyzeKotlin } from "../src/analyze.ts";
import { validateExpression } from "../src/expression.ts";
import { twinCases } from "./twins.ts";

const opts = { prefix: "data-", checkHtmlAttributes: true };

interface Expected {
  code: string;
  text: string;
  titles: string[];
  link?: string;
}
interface Case {
  name: string;
  language: "kotlin" | "html" | "expression";
  source: string;
  issues: Expected[];
}

const run = { kotlin: (s: string) => analyzeKotlin(s, opts), html: (s: string) => analyzeHtml(s, opts), expression: validateExpression };
const byCode = (a: { code?: string }, b: { code?: string }) => ((a.code ?? "") < (b.code ?? "") ? -1 : (a.code ?? "") > (b.code ?? "") ? 1 : 0);

// The titles of the quick fixes, each marked when it may not be preferred, and the links of the issues, as FixesTest.kt reads them from the same file; the source each fix leaves is in the shared corpus.
describe("quick fix titles and links", () => {
  for (const c of twinCases<Case>("fixes.json")) {
    it(c.name, () => {
      const seen = [...run[c.language](c.source)]
        .sort((a, b) => a.start - b.start || a.end - b.end || byCode(a, b))
        .map((i, n) => {
          const o: Expected = { code: i.code ?? "", text: c.source.slice(i.start, i.end), titles: (i.fixes ?? []).map((f) => f.title + (f.preferred === false ? " (not preferred)" : "")) };
          if (c.issues[n]?.link !== undefined) o.link = i.link;
          return o;
        });
      assert.deepEqual(seen, c.issues);
    });
  }
});

describe("generated files", () => {
  it("html custom data and snippets are current and well-formed", () => {
    const data = JSON.parse(readFileSync(new URL("../html-customdata.json", import.meta.url), "utf8"));
    assert.equal(data.version, 1.1);
    const names = data.globalAttributes.map((a: { name: string }) => a.name);
    assert.ok(names.includes("data-on") && names.includes("data-persist"));
    assert.ok(data.globalAttributes.every((a: { description: { value: string }; references: unknown[] }) => a.description.value.length > 10 && a.references.length === 1));
    const snippets = JSON.parse(readFileSync(new URL("../snippets/html.json", import.meta.url), "utf8"));
    assert.equal(snippets["data-on"].body, 'data-on:${1:click}="${2:expression}"');
    const kotlin = JSON.parse(readFileSync(new URL("../snippets/kotlin.json", import.meta.url), "utf8"));
    assert.ok(Object.keys(kotlin).length >= 10);
  });
});
