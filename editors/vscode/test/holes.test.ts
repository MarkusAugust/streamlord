import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { holeLandings, urlSchemeFixedBy } from "../src/holes.ts";

/** The cases core's HoleLandingsTest holds to interpolate; this port is held to the same file. */
const fixture = JSON.parse(readFileSync(new URL("./fixtures/twins/hole-landings.json", import.meta.url), "utf8")) as {
  cases: { name: string; markup: string; landings: { position: string; attribute: string | null }[] }[];
  urls: { attribute: string; prefix: string; fixed: boolean }[];
};

describe("hole landings", () => {
  it("lands each hole where core does", () => {
    assert.ok(fixture.cases.length > 0);
    for (const c of fixture.cases) {
      const holes = [...c.markup.matchAll(/%s/g)].map((m) => ({ start: m.index, end: m.index + 2 }));
      const actual = holeLandings(c.markup, holes).map((l) => ({ position: l.position, attribute: l.attribute }));
      assert.deepEqual(actual, c.landings, c.name);
    }
  });

  it("knows when the start of a URL settles its scheme, as core does", () => {
    assert.ok(fixture.urls.length > 0);
    for (const u of fixture.urls) assert.equal(urlSchemeFixedBy(u.attribute, u.prefix), u.fixed, `${u.attribute}="${u.prefix}…"`);
  });
});
