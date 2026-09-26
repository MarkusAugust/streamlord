import { codeMask } from "./scanner.ts";

/**
 * Finds HTTP routes in Kotlin source for the "Open in Stream Inspector" code lens.
 * Ktor: `route("/api") { get("/x") { } }`, verbs without a path inherit the enclosing route.
 * Spring: `@RequestMapping("/api")` on the class, `@GetMapping("/x")` and friends on methods.
 */

export interface Route {
  method: string;
  path: string;
  /** Source offset of the verb or annotation, for the lens position. */
  offset: number;
  framework: "ktor" | "spring";
}

const KTOR_VERBS = new Set(["get", "post", "put", "patch", "delete"]);
const SPRING_MAPPINGS: Record<string, string | null> = { GetMapping: "GET", PostMapping: "POST", PutMapping: "PUT", PatchMapping: "PATCH", DeleteMapping: "DELETE", RequestMapping: null };

export function findRoutes(src: string): Route[] {
  return [...findKtorRoutes(src), ...findSpringRoutes(src)].sort((a, b) => a.offset - b.offset);
}

function joinPath(prefix: string, path: string): string {
  const joined = `${prefix}/${path}`.replace(/\/{2,}/g, "/");
  return joined.length > 1 ? joined.replace(/\/$/, "") : joined || "/";
}

function findKtorRoutes(src: string): Route[] {
  const routes: Route[] = [];
  const mask = codeMask(src);
  // Stack of { prefix, depth } for enclosing route("...") { } blocks.
  const stack: { prefix: string; depth: number }[] = [];
  let depth = 0;
  const re = /\b(route|get|post|put|patch|delete)\s*(?:\(\s*"([^"\n]*)"\s*\)\s*)?\{|\b(get|post|put|patch|delete)\s*\(\s*"([^"\n]*)"\s*\)/g;
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (mask[i] === 0) {
      i++;
      continue;
    }
    if (c === "{") {
      depth++;
      i++;
      continue;
    }
    if (c === "}") {
      depth--;
      while (stack.length && (stack[stack.length - 1]?.depth ?? 0) > depth) stack.pop();
      i++;
      continue;
    }
    re.lastIndex = i;
    const m = re.exec(src);
    if (!m || m.index !== i) {
      i++;
      continue;
    }
    const prefix = stack[stack.length - 1]?.prefix ?? "";
    if (m[1] !== undefined) {
      const name = m[1];
      const path = m[2] ?? "";
      if (name === "route") {
        stack.push({ prefix: joinPath(prefix, path), depth: depth + 1 });
      } else if (KTOR_VERBS.has(name) && (path || stack.length > 0)) {
        routes.push({ method: name.toUpperCase(), path: joinPath(prefix, path), offset: m.index, framework: "ktor" });
      }
      depth++;
      i = m.index + m[0].length;
      continue;
    }
    if (m[3] !== undefined) {
      routes.push({ method: m[3].toUpperCase(), path: joinPath(prefix, m[4] ?? ""), offset: m.index, framework: "ktor" });
      i = m.index + m[0].length;
      continue;
    }
    i++;
  }
  return routes;
}

function annotationPath(args: string): string {
  const named = /(?:value|path)\s*=\s*(?:\[\s*)?"([^"]*)"/.exec(args);
  if (named) return named[1] ?? "";
  const positional = /^\s*(?:\[\s*)?"([^"]*)"/.exec(args);
  return positional?.[1] ?? "";
}

function annotationMethod(args: string): string | null {
  const m = /RequestMethod\.([A-Z]+)/.exec(args);
  return m?.[1] ?? null;
}

function findSpringRoutes(src: string): Route[] {
  const routes: Route[] = [];
  const mask = codeMask(src);
  const re = /@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\s*(?:\(([^)]*)\))?/g;
  let classPrefix = "";
  let m: RegExpExecArray | null;
  while ((m = re.exec(src)) !== null) {
    if (mask[m.index] === 0) continue;
    const name = m[1] ?? "";
    const args = m[2] ?? "";
    const after = src.slice(m.index + m[0].length, m.index + m[0].length + 200);
    const onClass = /^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:open\s+|abstract\s+)?class\b/.test(after);
    if (onClass) {
      if (name === "RequestMapping") classPrefix = annotationPath(args);
      continue;
    }
    const method = SPRING_MAPPINGS[name] === null ? (annotationMethod(args) ?? "GET") : (SPRING_MAPPINGS[name] ?? "GET");
    routes.push({ method, path: joinPath(classPrefix, annotationPath(args)), offset: m.index, framework: "spring" });
  }
  return routes;
}
