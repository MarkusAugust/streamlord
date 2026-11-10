import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { HoleFact } from "../src/analyze.ts";
import type { Position } from "../src/holes.ts";
import { verdict } from "../src/valueVerdict.ts";
import { twinCases } from "./twins.ts";

type Case = { name: string; fact: HoleFact | null; verdicts: Record<Position, string> };

const POSITIONS: Position[] = ["TEXT", "ATTRIBUTE", "URL", "DATASTAR", "STYLE", "STRUCTURE", "CODE"];

describe("value verdict", () => {
  for (const c of twinCases<Case>("value-verdict.json")) {
    it(c.name, () => {
      assert.deepEqual(Object.keys(c.verdicts).sort(), [...POSITIONS].sort());
      for (const p of POSITIONS) assert.equal(verdict(c.fact, p), c.verdicts[p], p);
    });
  }
});
