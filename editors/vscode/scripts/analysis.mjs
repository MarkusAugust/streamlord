#!/usr/bin/env node
/**
 * Builds the analysis the extension runs: streamlord-analysis compiled to JavaScript by the Gradle
 * project in analysis-js, with its TypeScript types. Run before `build`, `test` and `typecheck`;
 * Gradle does nothing when no Kotlin source has changed.
 */
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../../..", import.meta.url));
const gradlew = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
const done = spawnSync(gradlew, [":analysis-js:port", "--quiet", "--console=plain"], { cwd: root, stdio: "inherit", shell: process.platform === "win32" });
process.exit(done.status ?? 1);
