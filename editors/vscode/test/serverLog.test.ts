import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { isProblem, RunningServers, startedAt } from "../src/serverLog.ts";
import { twinCases } from "./twins.ts";

/** A case of `fixtures/twins/server-log.json`, which ServerLogTest.kt in streamlord-analysis reads too. */
interface Case {
  name: string;
  call: "startedAt" | "isProblem";
  line: string;
  expected: string | boolean | null;
}

describe("server log", () => {
  for (const c of twinCases<Case>("server-log.json")) {
    const read = { startedAt, isProblem }[c.call];
    it(c.name, () => assert.equal(read(c.line), c.expected, c.name));
  }

  // The steps of the IntelliJ plugin's test of its own RunningServers.
  it("follows a server that starts, logs and stops", () => {
    const servers = new RunningServers();
    let changes = 0;
    servers.onChange(() => changes++);
    servers.feed("1", "Motregning back (dev)", "\u001B[32m INFO\u001B[0;39m Starting Application\n2026-10-06 INFO Tomcat started on port 91", 1);
    assert.equal(servers.current(), null);
    servers.feed("1", "Motregning back (dev)", "02 (http) with context path '/'\n", 2);
    assert.deepEqual(servers.current(), { url: "http://localhost:9102", name: "Motregning back (dev)" });
    servers.feed("1", "Motregning back (dev)", "INFO all is well\n\u001B[33m WARN\u001B[0;39m Resolved [MissingServletRequestParameterException]\n", 3);
    assert.deepEqual(servers.problemsSince(3), [" WARN Resolved [MissingServletRequestParameterException]"]);
    assert.deepEqual(servers.problemsSince(4), []);
    servers.end("1");
    assert.equal(servers.current(), null);
    assert.equal(changes, 2);
  });
});
