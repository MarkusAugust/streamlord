import { findCallSites } from "./scanner.ts";

/**
 * Collects signal names declared anywhere in a source file, for completion. Heuristic and
 * generous: better to offer a name twice than to miss it.
 */

const KOTLIN_PATTERNS: RegExp[] = [
  /\b(?:signal|set|setExpr|increment|decrement|toggle|not|dataBind|dataIndicator|dataRef|dataComputed|dataMatchMedia)\(\s*"([A-Za-z_][A-Za-z0-9_.]*)"/g,
  /\bdataSignals\(\s*"([A-Za-z_][A-Za-z0-9_.]*)"\s*,/g,
  /\$\{'\$'\}([A-Za-z_][A-Za-z0-9_.]*)/g,
  /\\\$([A-Za-z_][A-Za-z0-9_.]*)/g,
];

const HTML_PATTERNS: RegExp[] = [
  /data-(?:star-)?(?:signals|bind|indicator|ref|computed|match-media):([A-Za-z_][A-Za-z0-9_.-]*)/g,
  /data-(?:star-)?(?:bind|indicator|ref)(?:__[^=\s]*)?="([A-Za-z_][A-Za-z0-9_.]*)"/g,
  /data-(?:star-)?signals(?:__[^=\s]*)?="\{([^"]*)\}"/g,
  /\$([A-Za-z_][A-Za-z0-9_.]*)/g,
];

const SERIALIZABLE_CLASS = /@Serializable\s*(?:\([^)]*\))?\s*(?:data\s+)?class\s+\w+\s*\(([^)]*)\)/g;
const PROPERTY = /\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:/g;

const PAIR_CALLS: ReadonlySet<string> = new Set(["dataSignals", "patchSignals", "removeSignals", "respondSignals", "datastarSignals"]);
const PAIR = /"([A-Za-z_][A-Za-z0-9_.]*)"(?:\s+to\b|\s*,|\s*\)|\s*$)/g;

export function collectSignals(src: string, language: "kotlin" | "html"): Set<string> {
  const out = new Set<string>();
  if (language === "kotlin") {
    for (const site of findCallSites(src, PAIR_CALLS)) {
      const inner = src.slice(site.openParen + 1, site.closeParen);
      PAIR.lastIndex = 0;
      let pm: RegExpExecArray | null;
      while ((pm = PAIR.exec(inner)) !== null) if (pm[1] && !/^(true|false)$/.test(pm[1])) out.add(pm[1]);
    }
  }
  const patterns = language === "kotlin" ? KOTLIN_PATTERNS : HTML_PATTERNS;
  for (const re of patterns) {
    re.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(src)) !== null) {
      const captured = m[1] ?? "";
      if (re.source.includes("signals(?:__")) {
        for (const key of captured.matchAll(/(?:^|[{,])\s*([A-Za-z_][A-Za-z0-9_]*)\s*:/g)) {
          if (key[1]) out.add(key[1]);
        }
      } else if (captured) {
        out.add(toCamel(captured));
      }
    }
  }
  if (language === "kotlin") {
    SERIALIZABLE_CLASS.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = SERIALIZABLE_CLASS.exec(src)) !== null) {
      PROPERTY.lastIndex = 0;
      let p: RegExpExecArray | null;
      const body = m[1] ?? "";
      while ((p = PROPERTY.exec(body)) !== null) if (p[1]) out.add(p[1]);
    }
  }
  return out;
}

/** Datastar exposes kebab-case keys as camelCase signals by default. */
function toCamel(name: string): string {
  return name.includes("-") ? name.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase()) : name;
}
