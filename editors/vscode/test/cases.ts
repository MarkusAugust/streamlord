import { readFileSync } from "node:fs";

/**
 * The cases of a rule both twins hold, from `fixtures/twins/<file>`: these tests and the Kotlin
 * analysis's read the same file, through `TwinCases.kt` there, so a case is written once and the
 * two analyses cannot hold different ones.
 */
export const twinCases = <T>(file: string): T[] =>
  (JSON.parse(readFileSync(new URL(`./fixtures/twins/${file}`, import.meta.url), "utf8")) as { cases: T[] }).cases;
