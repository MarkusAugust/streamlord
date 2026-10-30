import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { analyzeKotlin } from "../src/analyze.ts";

/**
 * The five files of the interpolation test project, and what the analysis must say about each
 * hole in them; InterpolationProjectTest.kt reads the same expected.json.
 */
const dir = new URL("./fixtures/interpolationtests/", import.meta.url);
const expected = JSON.parse(readFileSync(new URL("expected.json", dir), "utf8")) as {
  constants: string[];
  files: Record<string, { start: number; end: number; code: string; severity: string; message: string }[]>;
};
const CODES = new Set(["kotlin-interpolation", "unescaped-interpolation", "unsafe-interpolation"]);

describe("the interpolation test project", () => {
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
