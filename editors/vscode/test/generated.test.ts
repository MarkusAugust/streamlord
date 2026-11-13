import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { grammarText, kotlinDslInjectionGrammar, kotlinInjectionGrammar } from "../scripts/kotlinGrammar.mjs";

/** What `npm run build` writes from the catalog for VS Code's own HTML service and its snippets. */
describe("generated files", () => {
  it("html custom data and snippets are current and well-formed", () => {
    const data = JSON.parse(readFileSync(new URL("../html-customdata.json", import.meta.url), "utf8"));
    assert.equal(data.version, 1.1);
    const names = data.globalAttributes.map((a: { name: string }) => a.name);
    assert.ok(names.includes("data-on") && names.includes("data-persist"));
    assert.ok(data.globalAttributes.every((a: { description: { value: string }; references: unknown[] }) => a.description.value.length > 10 && a.references.length === 1));
    const snippets = JSON.parse(readFileSync(new URL("../snippets/html.json", import.meta.url), "utf8"));
    assert.equal(snippets["data-on"].body, 'data-on:${1:click}="${2:expression}"');
    // A value Datastar takes as text, not as an expression.
    assert.equal(snippets["data-nonce"].body, 'data-nonce="${2:value}"');
    assert.equal(snippets["data-preserve-attr"].body, 'data-preserve-attr="${2:value}"');
    const kotlin = JSON.parse(readFileSync(new URL("../snippets/kotlin.json", import.meta.url), "utf8"));
    assert.ok(Object.keys(kotlin).length >= 10);
  });

  // A hand edit to a generated grammar is lost at the next build: the generator is what to change.
  it("the Kotlin injection grammars are what scripts/kotlinGrammar.mjs writes from the catalog", () => {
    const catalog = JSON.parse(readFileSync(new URL("../../../catalog/datastar-1.0.5.json", import.meta.url), "utf8"));
    assert.equal(readFileSync(new URL("../syntaxes/kotlin-injection.tmLanguage.json", import.meta.url), "utf8"), grammarText(kotlinInjectionGrammar(catalog)));
    assert.equal(readFileSync(new URL("../syntaxes/kotlin-dsl-injection.tmLanguage.json", import.meta.url), "utf8"), grammarText(kotlinDslInjectionGrammar()));
  });
});
