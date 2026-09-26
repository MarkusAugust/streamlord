#!/usr/bin/env node
/**
 * Generates what VS Code can serve on its own from the catalog: HTML custom data for the
 * built-in HTML language service (attribute completion and hover in .html files) and snippets
 * for Kotlin and HTML. Run by `npm run build`; the outputs are committed so the vsix is
 * reproducible without this step.
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const catalog = JSON.parse(readFileSync(join(root, "../../catalog/datastar-1.0.4.json"), "utf8"));
const groups = catalog.modifierGroups;

const expand = (mods) => mods.flatMap((m) => (m.$ref ? groups[m.$ref] : [m]));
const modifierText = (m) => {
  switch (m.type) {
    case "flag":
      return `__${m.name}`;
    case "duration":
      return `__${m.name}.500ms` + (m.flags?.length ? ` (.${m.flags.join(", .")})` : "");
    case "enum":
      return `__${m.name}.${m.values.join("|")}`;
    case "int":
      return `__${m.name}.50`;
    case "ident":
      return `__${m.name}.name`;
    case "idents":
      return `__${m.name}.a.b`;
    default:
      return `__${m.name}`;
  }
};

const docFor = (a) => {
  const mods = expand(a.modifiers);
  const lines = [a.doc, "", ...a.forms.map((f) => "`" + f + "`")];
  if (mods.length) lines.push("", "**Modifiers:** " + mods.map((m) => "`" + modifierText(m) + "`").join(", "));
  if (a.pro) lines.push("", "_Datastar Pro. Requires the Pro bundle, which you license and load yourself._");
  return lines.join("\n");
};

// ---- HTML custom data ---------------------------------------------------------------------
const customData = {
  version: 1.1,
  globalAttributes: catalog.attributes.map((a) => ({
    name: "data-" + a.name,
    description: { kind: "markdown", value: docFor(a) },
    references: [{ name: "Datastar reference", url: `https://data-star.dev/reference/attributes#data-${a.name}` }],
  })),
};
writeFileSync(join(root, "html-customdata.json"), JSON.stringify(customData, null, 2) + "\n");

// ---- HTML snippets -------------------------------------------------------------------------
const htmlSnippets = {};
for (const a of catalog.attributes) {
  const key = a.keyed ? (a.keyRequired ? ":${1:" + (a.name === "on" ? "click" : "name") + "}" : "") : "";
  const value = a.valueKind === "none" ? "" : a.valueKind === "signal" ? '="${2:signal}"' : a.valueKind === "filter" ? '="${2:{include: /.*/\\}}"' : '="${2:expression}"';
  htmlSnippets[`data-${a.name}`] = {
    prefix: `data-${a.name}`,
    body: `data-${a.name}${key}${value}`,
    description: (a.pro ? "Pro: " : "") + a.doc,
  };
}
htmlSnippets["data-on:click @post"] = { prefix: "data-on:click", body: `data-on:click="@post('\${1:/path}')"`, description: "Post signals to the server on click." };
htmlSnippets["data-init @get"] = { prefix: "data-init", body: `data-init="@get('\${1:/path}')"`, description: "Open a stream when the element appears." };
mkdirSync(join(root, "snippets"), { recursive: true });
writeFileSync(join(root, "snippets/html.json"), JSON.stringify(htmlSnippets, null, 2) + "\n");

console.log(`generated html-customdata.json (${customData.globalAttributes.length} attributes) and snippets/html.json (${Object.keys(htmlSnippets).length} snippets)`);
