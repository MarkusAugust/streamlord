import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { collectSignalDefinitions, collectSignals } from "../src/signals.ts";
import { twinCases } from "./twins.ts";

type Case = { name: string; language: "kotlin" | "html"; src: string; signals?: string[]; definitions?: string[]; encoded?: string[] };

describe("signal collection", () => {
  for (const c of twinCases<Case>("signals.json")) {
    it(c.name, () => {
      const definitions = [...collectSignalDefinitions(c.src, c.language)];
      if (c.signals) assert.deepEqual([...collectSignals(c.src, c.language)].sort(), c.signals, c.name);
      if (c.definitions) assert.deepEqual([...definitions].sort(), c.definitions, c.name);
      if (c.encoded) assert.deepEqual(definitions.filter((d) => d === "*" || d.includes(":")), c.encoded, c.name);
    });
  }
});
