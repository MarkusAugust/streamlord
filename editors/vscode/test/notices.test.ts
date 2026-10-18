import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

/**
 * The extension bundles its one dependency into dist/extension.js, so the notice its licence asks
 * for ships beside it in ThirdPartyNotices.txt (`vsce package` lists it). Held to the licence the
 * installed package carries, so an upgrade that changes it fails here.
 */
describe("third-party notices", () => {
  it("carry the licence of every bundled dependency", () => {
    const pkg = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8")) as { dependencies: Record<string, string> };
    const notices = readFileSync(new URL("../ThirdPartyNotices.txt", import.meta.url), "utf8");
    assert.deepEqual(Object.keys(pkg.dependencies), ["acorn"]);
    for (const name of Object.keys(pkg.dependencies)) {
      const licence = readFileSync(new URL(`../node_modules/${name}/LICENSE`, import.meta.url), "utf8");
      assert.ok(notices.includes(licence), `ThirdPartyNotices.txt lacks the licence of ${name}`);
    }
  });
});
