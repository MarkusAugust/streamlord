import raw from "../../../catalog/datastar-1.0.4.json" with { type: "json" };

export type ModifierType = "flag" | "duration" | "enum" | "int" | "ident" | "idents";

export interface Modifier {
  name: string;
  type: ModifierType;
  flags?: string[];
  values?: string[];
  min?: number;
  max?: number;
}

export interface AttributeSpec {
  name: string;
  pro: boolean;
  forms: string[];
  keyed: boolean;
  keyRequired: boolean;
  /**
   * How Datastar reads the key, which the browser has lowercased: `camel` turns `foo-bar` into
   * the signal `fooBar`; `kebab` keeps it unless `__case` says otherwise (events, classes);
   * `raw` uses it as it is (attributes, style properties). Absent on keyless attributes.
   */
  keyCase?: "camel" | "kebab" | "raw";
  valueKind: "expression" | "signal" | "filter" | "text" | "none";
  kotlin: string[];
  modifiers: Modifier[];
  onlyOn?: string[];
  doc: string;
}

export interface ActionSpec {
  name: string;
  pro: boolean;
  kind: string;
  signature: string;
  kotlin: string;
  doc: string;
}

export interface CallSiteSpec {
  arg: number | "last";
  named?: string;
  selectorArg?: string;
  modeArg?: string;
  onlyIfStringArgs?: boolean;
}

export interface Catalog {
  version: string;
  prefix: string;
  aliasedPrefix: string;
  attributes: AttributeSpec[];
  attributesByName: Map<string, AttributeSpec>;
  attributesByKotlin: Map<string, AttributeSpec>;
  actions: ActionSpec[];
  actionsByName: Map<string, ActionSpec>;
  fetchOptions: { name: string; type: string; default: string }[];
  fetchEvents: string[];
  patchModes: string[];
  callSites: {
    expression: Record<string, CallSiteSpec>;
    html: Record<string, CallSiteSpec>;
    script: Record<string, CallSiteSpec>;
    selector: Record<string, CallSiteSpec>;
  };
}

type RawModifier = { $ref?: string; name?: string; type?: ModifierType; flags?: string[]; values?: string[]; min?: number; max?: number };

function expandModifiers(mods: RawModifier[], groups: Record<string, RawModifier[]>): Modifier[] {
  const out: Modifier[] = [];
  for (const m of mods) {
    if (m.$ref) {
      for (const g of groups[m.$ref] ?? []) out.push(toModifier(g));
    } else {
      out.push(toModifier(m));
    }
  }
  return out;
}

function toModifier(m: RawModifier): Modifier {
  const mod: Modifier = { name: m.name ?? "", type: m.type ?? "flag" };
  if (m.flags) mod.flags = m.flags;
  if (m.values) mod.values = m.values;
  if (m.min !== undefined) mod.min = m.min;
  if (m.max !== undefined) mod.max = m.max;
  return mod;
}

function build(): Catalog {
  const groups = raw.modifierGroups as unknown as Record<string, RawModifier[]>;
  const attributes: AttributeSpec[] = (raw.attributes as unknown as Record<string, unknown>[]).map((a) => ({
    name: a.name as string,
    pro: (a.pro as boolean | undefined) ?? false,
    forms: a.forms as string[],
    keyed: a.keyed as boolean,
    keyRequired: (a.keyRequired as boolean | undefined) ?? false,
    keyCase: a.keyCase as AttributeSpec["keyCase"],
    valueKind: a.valueKind as AttributeSpec["valueKind"],
    kotlin: a.kotlin as string[],
    modifiers: expandModifiers(a.modifiers as RawModifier[], groups),
    onlyOn: a.onlyOn as string[] | undefined,
    doc: a.doc as string,
  }));
  const actions: ActionSpec[] = (raw.actions as unknown as Record<string, unknown>[]).map((a) => ({
    name: a.name as string,
    pro: (a.pro as boolean | undefined) ?? false,
    kind: a.kind as string,
    signature: a.signature as string,
    kotlin: a.kotlin as string,
    doc: a.doc as string,
  }));
  const attributesByKotlin = new Map<string, AttributeSpec>();
  for (const a of attributes) for (const k of a.kotlin) attributesByKotlin.set(k, a);
  const sites = raw.kotlinCallSites as unknown as Record<string, Record<string, CallSiteSpec>>;
  return {
    version: raw.version,
    prefix: raw.prefix,
    aliasedPrefix: raw.aliasedPrefix,
    attributes,
    attributesByName: new Map(attributes.map((a) => [a.name, a])),
    attributesByKotlin,
    actions,
    actionsByName: new Map(actions.map((a) => [a.name, a])),
    fetchOptions: raw.fetchOptions,
    fetchEvents: raw.fetchEvents,
    patchModes: raw.sse.patchModes,
    callSites: {
      expression: sites.expression ?? {},
      html: sites.html ?? {},
      script: sites.script ?? {},
      selector: sites.selector ?? {},
    },
  };
}

export const catalog: Catalog = build();

/** Parse a rendered attribute name such as `data-on:click__once__debounce.500ms` into its parts. */
export interface ParsedAttribute {
  base: string;
  key: string | null;
  modifiers: { text: string; name: string; args: string[]; offset: number }[];
  spec: AttributeSpec | undefined;
}

export function parseAttributeName(name: string, prefix: string): ParsedAttribute | null {
  if (!name.startsWith(prefix)) return null;
  const rest = name.slice(prefix.length);
  const parts = rest.split("__");
  const head = parts[0] ?? "";
  const colon = head.indexOf(":");
  const base = colon >= 0 ? head.slice(0, colon) : head;
  const key = colon >= 0 ? head.slice(colon + 1) : null;
  const modifiers: ParsedAttribute["modifiers"] = [];
  let offset = prefix.length + head.length;
  for (const part of parts.slice(1)) {
    offset += 2;
    const [mname = "", ...args] = part.split(".");
    modifiers.push({ text: part, name: mname, args, offset });
    offset += part.length;
  }
  return { base, key, modifiers, spec: catalog.attributesByName.get(base) };
}

/** Levenshtein distance, for "did you mean" hints. */
export function distance(a: string, b: string): number {
  const dp: number[] = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let prev = dp[0] ?? 0;
    dp[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const tmp = dp[j] ?? 0;
      dp[j] = Math.min((dp[j] ?? 0) + 1, (dp[j - 1] ?? 0) + 1, prev + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = tmp;
    }
  }
  return dp[b.length] ?? 0;
}
