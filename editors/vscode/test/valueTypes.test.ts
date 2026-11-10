import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { isMarkupCollection, opensWithTag, valueFactOfTypeName } from "../src/valueTypes.ts";
import { twinCases } from "./twins.ts";

type Case = { name: string; type?: string; fact?: string | null; collection?: string; markup?: boolean; content?: string; trimMargin?: boolean; tag?: boolean };

describe("value types", () => {
  for (const c of twinCases<Case>("value-types.json")) {
    it(c.name, () => {
      const asked = ([["type", "fact"], ["collection", "markup"], ["content", "tag"]] as const).filter(([q]) => q in c);
      assert.equal(asked.length, 1, "a case asks one of type, collection and content");
      assert.ok(asked[0]![1] in c, `the case gives no ${asked[0]![1]}`);
      if (c.type !== undefined) assert.equal(valueFactOfTypeName(c.type), c.fact);
      if (c.collection !== undefined) assert.equal(isMarkupCollection(c.collection), c.markup);
      if (c.content !== undefined) assert.equal(opensWithTag(c.content, c.trimMargin ?? false), c.tag);
    });
  }
});
