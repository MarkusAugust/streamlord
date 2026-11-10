import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { valueAtCaret } from "../src/completionContext.ts";
import { tokenize } from "../src/markup.ts";
import { twinCases } from "./twins.ts";

/**
 * The checks and the caret read markup the one way: every quoted attribute value tokenize finds,
 * valueAtCaret finds at each offset in it, and every value the caret finds is one tokenize found.
 * Over the markup of the shared fixtures, read as a template file and as a Kotlin string, so the
 * two cannot drift apart where the fixtures already reach. OneReadingTest.kt holds the same.
 */
const corpus = (file: string) =>
  (JSON.parse(readFileSync(new URL(`./fixtures/corpus/${file}`, import.meta.url), "utf8")) as { cases: { name: string; source: string }[] }).cases.map((c) => [c.name, c.source] as const);

const markups: (readonly [string, string])[] = [
  ...twinCases<{ name: string; markup: string }>("hole-landings.json").map((c) => [c.name, c.markup] as const),
  ...twinCases<{ name: string; before: string; after: string }>("completion-contexts.json").map((c) => [c.name, c.before + c.after] as const),
  ...corpus("markup.json"),
  ...corpus("html.json"),
];

describe("one reading of markup", () => {
  it("the caret finds every quoted value the checks read", () => {
    for (const [name, markup] of markups) {
      for (const templates of [true, false]) {
        for (const tag of tokenize(markup, templates).tags) {
          for (const attr of tag.attributes) {
            if (!attr.quoted || attr.value === null) continue;
            for (let offset = attr.valueStart; offset <= attr.valueStart + attr.value.length; offset++) {
              const at = valueAtCaret(markup, offset, "data-", templates);
              assert.deepEqual([at?.attribute, at?.valueStart], [attr.name, attr.valueStart], `${name}, templates ${templates}, at ${offset}`);
            }
          }
        }
      }
    }
  });

  it("the checks read every value the caret finds", () => {
    for (const [name, markup] of markups) {
      for (const templates of [true, false]) {
        const values = tokenize(markup, templates).tags.flatMap((t) => t.attributes).filter((a) => a.quoted && a.value !== null);
        for (let offset = 0; offset <= markup.length; offset++) {
          const at = valueAtCaret(markup, offset, "data-", templates);
          if (at === null) continue;
          assert.ok(
            values.some((a) => a.name === at.attribute && a.valueStart === at.valueStart && offset <= a.valueStart + (a.value as string).length),
            `${name}, templates ${templates}: the caret at ${offset} finds ${at.attribute} at ${at.valueStart}`,
          );
        }
      }
    }
  });
});
