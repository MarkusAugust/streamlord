import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { isProblem, RunningServers, startedAt } from "../src/serverLog.ts";

/** The cases of the analysis module's `ServerLogTest.kt`, and the IntelliJ plugin's test of its RunningServers. */
describe("server log", () => {
  it("reads where a server says it started", () => {
    const cases: [string, string | null][] = [
      ["2026-10-06 11:39:50,123 [main] INFO  o.s.b.w.e.tomcat.TomcatWebServer - Tomcat started on port 9102 (http) with context path '/'", "http://localhost:9102"],
      ["Tomcat started on port 8080 (http) with context path '/app/'", "http://localhost:8080/app"],
      ["Tomcat started on ports 8443 (https), 8080 (http) with context path ''", "https://localhost:8443"],
      ["Tomcat started on port(s): 8080 (http) with context path ''", "http://localhost:8080"],
      ["Jetty started on port 8080 (http/1.1) with context path '/'", "http://localhost:8080"],
      ["Netty started on port 8080 (http)", "http://localhost:8080"],
      ["INFO  ktor.application - Responding at http://0.0.0.0:8080", "http://localhost:8080"],
      ["Responding at http://127.0.0.1:8081", "http://127.0.0.1:8081"],
      ["Responding at https://[::]:8443", "https://localhost:8443"],
      ["Responding at http://[::1]:8080", "http://[::1]:8080"],
      ["\u001B[32m INFO\u001B[0;39m Tomcat started on port \u001B[1m9102\u001B[0m (http) with context path '/'", "http://localhost:9102"],
      ["Tomcat initialized with port 8080 (http)", null],
      ["Started Application in 2.1 seconds", null],
    ];
    for (const [line, url] of cases) assert.equal(startedAt(line), url, line);
  });

  it("knows a warning or an error from the rest of the log", () => {
    for (const line of [
      "2026-10-06 11:39:56,305 [ndler-158] WARN  o.s.w.s.m.s.DefaultHandlerExceptionResolver  - Resolved [MissingServletRequestParameterException]",
      "ERROR 4242 --- [main] o.s.boot.SpringApplication : Application run failed",
      "Oct 06, 2026 11:39:56 AM org.apache.catalina.core.StandardWrapperValve invoke SEVERE: Servlet.service() threw",
      "2026-10-06T11:39:56.305+02:00  WARN 4242 --- [nio-9102-exec-1] .w.s.m.s.DefaultHandlerExceptionResolver : Resolved",
      "2026-10-06T11:39:56.305+02:00 \u001B[31mERROR\u001B[0;39m \u001B[35m4242\u001B[0;39m --- [main] Application run failed",
    ]) {
      assert.equal(isProblem(line), true, line);
    }
    for (const line of ["INFO  Tomcat started on port 9102 (http)", "DEBUG warnings are off", "Forwarded to /error"]) assert.equal(isProblem(line), false, line);
  });

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
