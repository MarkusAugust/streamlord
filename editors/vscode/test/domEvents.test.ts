import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { complete } from "../src/analysis.ts";
import { domEventsFile, pinnedLibDom } from "../scripts/domEvents.mjs";

describe("DOM events", () => {
  it("catalog/dom-events.json is what the pinned TypeScript gives; npm run build writes it", () => {
    const committed = readFileSync(new URL("../../../catalog/dom-events.json", import.meta.url), "utf8");
    assert.equal(committed, domEventsFile(pinnedLibDom()));
  });

  it("the catalog offers the DOM events and Datastar's own", () => {
    const events = complete("<div data-on:", 13, false, "data-", [], [], [], true);
    const click = events.find((e) => e.label === "click");
    assert.equal(click?.doc, "[MDN](https://developer.mozilla.org/docs/Web/API/Element/click_event)");
    assert.ok(events.some((e) => e.label === "animationend" && e.detail === "DOM event"));
    assert.deepEqual(
      events.filter((e) => e.detail === "Datastar event").map((e) => e.label),
      ["datastar-fetch", "datastar-signal-patch"],
    );
  });
});
