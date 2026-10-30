import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { isKnownSignal, nearestSignal, signalReferencesInHtml, signalReferencesInKotlin, unknownSignalIssue } from "../src/signalReferences.ts";
import { collectSignalDefinitions, collectSignals } from "../src/signals.ts";
import { twinCases } from "./twins.ts";

type Case = {
  name: string;
  language: "kotlin" | "html";
  src: string;
  refs?: string[][];
  unknown?: string[];
  codes?: string[];
  messages?: string[];
  fixes?: string[][][];
  signals?: string[];
  definitions?: string[];
  defined?: string[];
  path: string;
  known?: boolean;
  nearest?: string | null;
};

describe("signal references", () => {
  for (const c of twinCases<Case>("signal-references.json")) {
    it(c.name, () => {
      if (c.defined) {
        const defined = new Set(c.defined);
        if (c.known !== undefined) assert.equal(isKnownSignal(c.path, defined), c.known, c.name);
        if (c.nearest !== undefined) assert.equal(nearestSignal(c.path, defined), c.nearest, c.name);
        return;
      }
      const { src, language } = c;
      const defined = collectSignalDefinitions(src, language);
      const refs = language === "kotlin" ? signalReferencesInKotlin(src, "data-") : signalReferencesInHtml(src, "data-");
      const issues = refs.flatMap((r) => unknownSignalIssue(r, defined) ?? []);
      if (c.refs) assert.deepEqual(refs.map((r) => [src.slice(r.start, r.end), r.name]), c.refs, c.name);
      if (c.unknown) assert.deepEqual(issues.map((i) => src.slice(i.start, i.end)), c.unknown, c.name);
      if (c.codes) assert.deepEqual(issues.map((i) => i.code), c.codes, c.name);
      if (c.messages) assert.deepEqual(issues.map((i) => i.message), c.messages, c.name);
      if (c.fixes) assert.deepEqual(issues.map((i) => (i.fixes ?? []).map((f) => [src.slice(f.start, f.end), f.text])), c.fixes, c.name);
      if (c.signals) assert.deepEqual([...collectSignals(src, language)].sort(), c.signals, c.name);
      if (c.definitions) assert.deepEqual([...defined].sort(), c.definitions, c.name);
    });
  }
});
