import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { findRoutes, type Route } from "../src/routes.ts";
import { twinCases } from "./twins.ts";

/** A case of `fixtures/twins/routes.json`, which RoutesTest.kt in streamlord-analysis reads too. */
interface Case {
  name: string;
  source: string[];
  expected: string[];
}

// As RoutesTest.kt shows a route: framework, method and path, then what its handler reads.
const shown = (r: Route): string =>
  [`${r.framework} ${r.method} ${r.path}`, ...r.query.map((q) => `?${q.name}${q.required ? "" : "?"}`), ...r.headers.map((h) => `H ${h.name}${h.required ? "" : "?"}`)].join(" ");

describe("route detection", () => {
  for (const c of twinCases<Case>("routes.json")) {
    it(c.name, () => assert.deepEqual(findRoutes(c.source.join("\n")).map(shown), c.expected, c.name));
  }
});
