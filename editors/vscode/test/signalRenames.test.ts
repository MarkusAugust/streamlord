import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { signalDeclarations, signalReadRenameText, signalRenameText } from "../src/signals.ts";
import { twinCases } from "./twins.ts";

type Case = { name: string; language?: "kotlin" | "html"; src?: string; at?: string; read?: string; rename: string; text: string | null };

describe("signal renames", () => {
  for (const c of twinCases<Case>("signal-renames.json")) {
    it(c.name, () => {
      if (c.read !== undefined) {
        assert.equal(signalReadRenameText(c.read, c.rename), c.text);
        return;
      }
      const src = c.src!;
      const d = signalDeclarations(src, c.language!).find((d) => src.slice(d.start, d.end) === c.at)!;
      assert.equal(signalRenameText(src, d, c.rename), c.text);
    });
  }
});
