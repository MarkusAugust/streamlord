import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { twinCases } from "./twins.ts";

// twins/request-urls.json, which RequestsTest.kt in streamlord-analysis and ClientRequestTest.kt in
// streamlord-core judge the analysis by. Node has the browser's URL, so this is where the cases
// themselves are held to it.
type UrlCase = { url: string; method: string; signals: string; expected: string };
describe("the url the client opens", () => {
  it("is, in each case, what the browser's own URLSearchParams.set gives", () => {
    const cases = twinCases<UrlCase>("request-urls.json").filter((c) => ["GET", "DELETE"].includes(c.method.toUpperCase()));
    assert.ok(cases.length > 0);
    for (const c of cases) {
      // The client re-serialises the rest of the query and the inspector keeps it as written, so the
      // two are compared as the parameters a server reads.
      const client = new URL(c.url, "http://h/");
      client.searchParams.set("datastar", c.signals);
      const ours = new URL(c.expected, "http://h/");
      assert.deepEqual([...ours.searchParams], [...client.searchParams], JSON.stringify(c));
      assert.equal(ours.hash, client.hash, JSON.stringify(c));
    }
  });
});
