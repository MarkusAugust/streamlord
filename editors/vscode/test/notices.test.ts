import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

/**
 * dist/extension.js bundles the analysis, which is Kotlin compiled to JavaScript and carries the
 * Kotlin standard library with it, so the notice its licence asks for ships beside it in
 * ThirdPartyNotices.txt (`vsce package` lists it). Nothing from npm is bundled: a dependency added
 * to package.json fails here until its licence is in the file.
 */
describe("third-party notices", () => {
  it("carry the licence of what the bundle holds", () => {
    const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8")) as { dependencies?: Record<string, string> };
    const notices = readFileSync(new URL("../ThirdPartyNotices.txt", import.meta.url), "utf8");
    assert.deepEqual(Object.keys(pkg.dependencies ?? {}), []);
    assert.match(notices, /The Kotlin standard library/);
    assert.match(notices, /Apache License\s+Version 2\.0, January 2004/);
    assert.match(notices, /END OF TERMS AND CONDITIONS/);
  });
});
