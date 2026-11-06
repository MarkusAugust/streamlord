import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { collectSignalDefinitions, collectSignals, signalDeclarations } from "../src/signals.ts";
import { catalog } from "../src/catalog.ts";
import { twinCases } from "./twins.ts";

type Case = { name: string; language: "kotlin" | "html"; withoutDsl?: boolean; src: string; signals?: string[]; definitions?: string[]; encoded?: string[]; declarations?: [string, string][] };

describe("signal collection", () => {
  for (const c of twinCases<Case>("signals.json")) {
    it(c.name, () => {
      const foreign = (name: string) => c.withoutDsl === true && catalog.signalNameHelpers.has(name);
      const definitions = [...collectSignalDefinitions(c.src, c.language, foreign)];
      if (c.signals) assert.deepEqual([...collectSignals(c.src, c.language, foreign)].sort(), c.signals, c.name);
      if (c.definitions) assert.deepEqual([...definitions].sort(), c.definitions, c.name);
      if (c.encoded) assert.deepEqual(definitions.filter((d) => d === "*" || d.includes(":")), c.encoded, c.name);
      const declarations = signalDeclarations(c.src, c.language, foreign);
      if (c.declarations) assert.deepEqual(declarations.map((d) => [d.name, c.src.slice(d.start, d.end)]), c.declarations, c.name);
      assert.deepEqual(new Set(declarations.map((d) => d.name)), new Set(definitions.filter((d) => d !== "*" && !d.includes(":"))), c.name);
    });
  }
});
