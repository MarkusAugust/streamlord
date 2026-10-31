import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { holeLandings, urlHostFixedBy, urlSchemeFixedBy } from "../src/holes.ts";

/** The cases core's HoleLandingsTest holds to interpolate; this port is held to the same file. */
const fixture = JSON.parse(readFileSync(new URL("./fixtures/twins/hole-landings.json", import.meta.url), "utf8")) as {
  cases: { name: string; markup: string; known?: Record<string, string>; landings: { position: string; attribute: string | null; loadsCode?: boolean }[] }[];
  urls: { attribute: string; prefix: string; fixed: boolean }[];
  hosts: { attribute: string; prefix: string; fixed: boolean }[];
};

describe("hole landings", () => {
  it("lands each hole where core does", () => {
    assert.ok(fixture.cases.length > 0);
    for (const c of fixture.cases) {
      const holes = [...c.markup.matchAll(/%s/g)].map((m) => ({ start: m.index, end: m.index + 2 }));
      const known = new Map(Object.entries(c.known ?? {}).map(([i, text]) => [Number(i), text]));
      const actual = holeLandings(c.markup, holes, undefined, known).map((l) => ({ position: l.position, attribute: l.attribute, loadsCode: l.loadsCode }));
      assert.deepEqual(actual, c.landings.map((l) => ({ ...l, loadsCode: l.loadsCode ?? false })), c.name);
    }
  });

  it("gives the start of a value in the markup it was handed, known text or not", () => {
    const markup = '<a %s="x%s">';
    const [name, url] = holeLandings(markup, [{ start: 3, end: 5 }, { start: 8, end: 10 }], undefined, new Map([[0, "href"]]));
    assert.deepEqual(name, { position: "TEXT", attribute: null, valueStart: -1, loadsCode: false });
    assert.equal(url?.position, "URL");
    assert.equal(url?.valueStart, 7);
    assert.equal(holeLandings('<a href="%s">', [{ start: 9, end: 11 }], undefined, new Map([[0, '/x" title="']]))[0]?.position, "TEXT");
  });

  // Each hole is looked up among spans found in order: HoleLandingsTest holds core to the same.
  it("judges many holes in time that grows with the markup", () => {
    const markup = '<li title="%s"><a href="/x/%s">%s</a></li>'.repeat(5000);
    const holes = [...markup.matchAll(/%s/g)].map((m) => ({ start: m.index, end: m.index + 2 }));
    const began = performance.now();
    assert.equal(holeLandings(markup, holes).length, 15000);
    const took = performance.now() - began;
    assert.ok(took < 3000, `15000 holes took ${took} ms`);
  });

  it("knows when the start of a URL settles its scheme, as core does", () => {
    assert.ok(fixture.urls.length > 0);
    for (const u of fixture.urls) assert.equal(urlSchemeFixedBy(u.attribute, u.prefix), u.fixed, `${u.attribute}="${u.prefix}…"`);
  });

  it("knows when the start of a code-loading URL fixes its host, as core does", () => {
    assert.ok(fixture.hosts.length > 0);
    for (const h of fixture.hosts) assert.equal(urlHostFixedBy(h.attribute, h.prefix), h.fixed, `${h.attribute}="${h.prefix}…"`);
  });
});
