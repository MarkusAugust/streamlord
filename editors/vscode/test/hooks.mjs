// Resolve the `vscode` module to the mock when running provider tests under node:test.
import { register } from "node:module";
import { pathToFileURL } from "node:url";

register(pathToFileURL(new URL("./vscode-resolver.mjs", import.meta.url).pathname), import.meta.url);
