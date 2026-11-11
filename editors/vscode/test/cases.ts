import { readFileSync } from "node:fs";

/**
 * The cases of a rule, written once in `fixtures/cases/<file>`. The analysis's tests read them on
 * the JVM and on Node, and a test of the extension reads one where it holds something of its own
 * to it.
 */
export const sharedCases = <T>(file: string): T[] =>
  (JSON.parse(readFileSync(new URL(`./fixtures/cases/${file}`, import.meta.url), "utf8")) as { cases: T[] }).cases;
