import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { Inspector } from "../src/inspector.ts";
import type { DatastarFrame } from "../src/sse.ts";

/** An inspector with a stand-in panel that records what it posts to the page. */
function inspector(): { apply: (signals: string, onlyIfMissing?: string) => void; posted: { type: string; [k: string]: unknown }[] } {
  const posted: { type: string }[] = [];
  const context = { subscriptions: [] as unknown[] };
  const store = { onDidChange: () => ({ dispose() {} }) };
  // The panel and applyFrame are private to the class.
  const i = new Inspector(context as never, store as never, null as never) as any;
  i.panel = { webview: { postMessage: (m: { type: string }) => posted.push(m) } };
  const apply = (signals: string, onlyIfMissing?: string) => {
    const args: Record<string, string> = { signals, ...(onlyIfMissing !== undefined ? { onlyIfMissing } : {}) };
    const frame: DatastarFrame = { event: "datastar-patch-signals", id: null, retry: null, args, raw: "", receivedAt: 0 };
    i.applyFrame(frame);
  };
  return { apply, posted: posted as { type: string; [k: string]: unknown }[] };
}

describe("inspector signal store", () => {
  // The client keeps the keys a patch set before the one it fails on (signals.ts in 1.0.4).
  it("shows the keys set before a patch failed, and the reason", () => {
    const { apply, posted } = inspector();
    apply('{"a":[1,2]}');
    apply('{"b":1,"a":{"0":7,"length":-1},"c":2}');
    const signals = posted.filter((m) => m.type === "signals").map((m) => JSON.stringify(m.signals));
    assert.deepEqual(signals, ['{"a":[1,2]}', '{"a":[7,2],"b":1}']);
    const errors = posted.filter((m) => m.type === "error").map((m) => m.message);
    // The client leaves its batch open after a throw, so the bindings on the page stop redrawing
    // (measured in Chrome).
    assert.deepEqual(errors, [
      "The signal store cannot take this patch: Invalid array length -1 in a signal patch. " +
        "Datastar's client throws on it too, and the bindings already on the page then stop redrawing until it is reloaded, " +
        "though the signals still change, element patches still land and new bindings show the current value.",
    ]);
    apply('{"d":3}');
    assert.equal(JSON.stringify(posted.at(-1)?.signals), '{"a":[7,2],"b":1,"d":3}');
  });

  // A patch Streamlord refuses on purpose says so, and nothing about the page standing still.
  it("says when Streamlord refuses a patch on purpose", () => {
    const { apply, posted } = inspector();
    apply('{"a":[1]}');
    apply('{"b":1,"o":{"__proto__":5},"c":2}');
    apply('{"a":{"20000":1}}');
    const signals = posted.filter((m) => m.type === "signals").map((m) => JSON.stringify(m.signals));
    assert.deepEqual(signals, ['{"a":[1]}', '{"a":[1],"b":1,"o":{}}', '{"a":[1],"b":1,"o":{}}']);
    const errors = posted.filter((m) => m.type === "error").map((m) => m.message);
    assert.deepEqual(errors, [
      "Streamlord refuses this signal patch on purpose: A key named __proto__ gives the object holding it a foreign prototype in Datastar's client, " +
        "which then throws on some later keys, replaces the object on a later patch, or with a second __proto__ writes into String.prototype; " +
        "Streamlord does not model it and refuses the patch. The store shown keeps the keys before it.",
      "Streamlord refuses this signal patch on purpose: Signal patch index 20000 is more than 10000 past the end of an array of 1: " +
        "the client takes it, and Streamlord does not fill memory with the holes. The store shown keeps the keys before it.",
    ]);
  });

  it("reads onlyIfMissing as the client reads it", () => {
    const { apply, posted } = inspector();
    apply('{"a":1}');
    apply('{"a":2,"b":2}', "\ufefftrue\u2028");
    apply('{"b":3,"c":3}', "\u001ctrue");
    assert.equal(JSON.stringify(posted.at(-1)?.signals), '{"a":1,"b":3,"c":3}');
  });
});
