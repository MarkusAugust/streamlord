import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { isMarkupCollection, opensWithTag, valueFactOfTypeName } from "../src/valueTypes.ts";
import { twinCases } from "./twins.ts";

type Case = { name: string; type?: string; fact?: string | null; collection?: string; markup?: boolean; content?: string; trimMargin?: boolean; tag?: boolean };

describe("value types", () => {
  for (const c of twinCases<Case>("value-types.json")) {
    it(c.name, () => {
      if (c.type !== undefined) assert.equal(valueFactOfTypeName(c.type), c.fact);
      if (c.collection !== undefined) assert.equal(isMarkupCollection(c.collection), c.markup);
      if (c.content !== undefined) assert.equal(opensWithTag(c.content, c.trimMargin ?? false), c.tag);
    });
  }
});
