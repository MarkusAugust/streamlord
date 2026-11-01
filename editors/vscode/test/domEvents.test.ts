import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { catalog } from "../src/catalog.ts";
import { domEventsFile, pinnedLibDom } from "../scripts/domEvents.mjs";

describe("DOM events", () => {
  it("catalog/dom-events.json is what the pinned TypeScript gives; npm run build writes it", () => {
    const committed = readFileSync(new URL("../../../catalog/dom-events.json", import.meta.url), "utf8");
    assert.equal(committed, domEventsFile(pinnedLibDom()));
  });

  it("the catalog offers the DOM events and Datastar's own", () => {
    const click = catalog.domEvents.find((e) => e.name === "click");
    assert.equal(click?.mdn, "https://developer.mozilla.org/docs/Web/API/Element/click_event");
    assert.ok(catalog.domEvents.some((e) => e.name === "animationend"));
    assert.deepEqual(
      catalog.datastarEvents.map((e) => e.name),
      ["datastar-fetch", "datastar-signal-patch"],
    );
  });
});
