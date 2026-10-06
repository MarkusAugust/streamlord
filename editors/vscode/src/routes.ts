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
  // Spring and Ktor both accept a mapping without its leading slash, `@RequestMapping("api")`,
  // and the inspector puts the path straight after {{baseUrl}}: http://localhost:8080api.
  const joined = `/${prefix}/${path}`.replace(/\/{2,}/g, "/");
  return joined.length > 1 ? joined.replace(/\/$/, "") : joined;
}

function findKtorRoutes(src: string): Route[] {
  const routes: Route[] = [];
  const mask = codeMask(src);
  // Stack of { prefix, depth } for enclosing route("...") { } blocks.
  const stack: { prefix: string; depth: number }[] = [];
  let depth = 0;
  // `map.get("key")` and `client.post("https://...")` have the shape of a route and are none. A verb
  // counts when it is not called on a receiver, and it either takes a lambda or its path starts with
  // a slash. The path may be followed by more arguments: `route("/v1", HttpMethod.Get) { }`.
  const re = /\b(route|get|post|put|patch|delete)\s*(?:\(\s*"([^"\n]*)"\s*(?:,[^(){}\n]*)?\)\s*)?\{|\b(get|post|put|patch|delete)\s*\(\s*"(\/[^"\n]*)"\s*\)/g;
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
    if (!m || m.index !== i || hasReceiver(src, i)) {
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

/** Whether the call at `start` is made on something: `client.post(...)`, also with the dot on the line above. */
function hasReceiver(src: string, start: number): boolean {
  let i = start - 1;
  while (i >= 0 && " \t\r\n".includes(src[i] ?? "x")) i--;
  return i >= 0 && src[i] === ".";
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

const MODIFIERS = new Set(["public", "internal", "private", "protected", "open", "final", "abstract", "sealed", "data", "inner"]);

/** The offset just past the annotation at `at`, arguments included, whatever parentheses its strings hold. */
function annotationEnd(src: string, mask: Uint8Array, at: number): number {
  let i = at + 1;
  while (i < src.length && /[\w.:]/.test(src[i] ?? "")) i++;
  let j = i;
  while (j < src.length && /\s/.test(src[j] ?? "")) j++;
  if (src[j] !== "(") return i;
  let depth = 0;
  for (; j < src.length; j++) {
    if (mask[j] === 0) continue;
    if (src[j] === "(") depth++;
    else if (src[j] === ")" && --depth === 0) return j + 1;
  }
  return src.length;
}

/** The offset of `class` or `interface` when what follows `from` is the rest of a class header, else -1. */
function annotatedClass(src: string, mask: Uint8Array, from: number): number {
  let i = from;
  for (;;) {
    while (i < src.length && (mask[i] === 0 || /\s/.test(src[i] ?? ""))) i++;
    if (src[i] === "@") {
      i = annotationEnd(src, mask, i);
      continue;
    }
    const word = /^[A-Za-z_]\w*/.exec(src.slice(i, i + 40))?.[0];
    if (word === "class" || word === "interface") return i;
    if (word === undefined || !MODIFIERS.has(word)) return -1;
    i += word.length;
  }
}

const DECLARATIONS = new Set(["class", "interface", "object", "fun", "val", "var", "typealias"]);

interface ClassBody {
  keyword: number;
  open: number;
  close: number;
}

/** Every class and interface with a body. A header ends at the first top level `{`, or at the next declaration when the class has none. */
function classBodies(src: string, mask: Uint8Array): ClassBody[] {
  const bodies: ClassBody[] = [];
  const re = /\b(?:class|interface)\b/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(src)) !== null) {
    if (mask[m.index] === 0 || src.slice(Math.max(0, m.index - 2), m.index) === "::") continue;
    let parens = 0;
    let open = -1;
    for (let i = m.index + m[0].length; i < src.length && open < 0; i++) {
      if (mask[i] === 0) continue;
      const c = src[i];
      if (c === "(") parens++;
      else if (c === ")") parens--;
      else if (c === "{" && parens === 0) open = i;
      else if (parens === 0 && /[A-Za-z_]/.test(c ?? "") && !/\w/.test(src[i - 1] ?? "")) {
        const word = /^\w+/.exec(src.slice(i, i + 20))?.[0] ?? "";
        if (DECLARATIONS.has(word)) break;
        i += word.length - 1;
      }
    }
    if (open < 0) continue;
    let depth = 0;
    let close = src.length;
    for (let i = open; i < src.length; i++) {
      if (mask[i] === 0) continue;
      if (src[i] === "{") depth++;
      else if (src[i] === "}" && --depth === 0) {
        close = i;
        break;
      }
    }
    bodies.push({ keyword: m.index, open, close });
  }
  return bodies;
}

function findSpringRoutes(src: string): Route[] {
  const routes: Route[] = [];
  const mask = codeMask(src);
  const bodies = classBodies(src, mask);
  // A method takes the mapping of the class it stands in, and only that one: a class without
  // @RequestMapping has no prefix, and Spring does not join the mapping of an enclosing class.
  const prefixes = new Map<number, string>();
  const re = /@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\b\s*(?:\(([^)]*)\))?/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(src)) !== null) {
    if (mask[m.index] === 0) continue;
    const name = m[1] ?? "";
    const args = m[2] ?? "";
    const target = annotatedClass(src, mask, annotationEnd(src, mask, m.index));
    if (target >= 0) {
      if (name === "RequestMapping") prefixes.set(target, annotationPath(args));
      continue;
    }
    const owner = bodies.filter((b) => b.open < m!.index && m!.index < b.close).at(-1);
    const prefix = owner ? (prefixes.get(owner.keyword) ?? "") : "";
    const method = SPRING_MAPPINGS[name] === null ? (annotationMethod(args) ?? "GET") : (SPRING_MAPPINGS[name] ?? "GET");
    routes.push({ method, path: joinPath(prefix, annotationPath(args)), offset: m.index, framework: "spring" });
  }
  return routes;
}
