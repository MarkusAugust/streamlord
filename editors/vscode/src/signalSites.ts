import { signalReadRenameText, signalRenameText, type SignalDeclaration } from "./signals.ts";
import type { SignalReference } from "./signalReferences.ts";

/**
 * A place in a file that names a signal, for go to definition, find references and rename: one
 * part of a read's path, `name` in `$user.name` for `user.name`, or the name a defining place
 * writes, with `declaration` saying how a new one is written there. The rules are those of the
 * IntelliJ plugin's `SignalPsiReference`.
 */
export interface SignalSite {
  start: number;
  end: number;
  signal: string;
  declaration?: SignalDeclaration;
}

/**
 * The sites of a file: each part of a read whose path up to it the workspace defines, so that
 * `$user.name.length` reads `user` in its first part and `user.name` in its second, and every
 * defining place. A read that a defining place covers, `$count = 1`, which assigns the signal,
 * is that place alone, so that a rename writes it once.
 */
export function signalSites(reads: readonly SignalReference[], declarations: readonly SignalDeclaration[], defined: ReadonlySet<string>): SignalSite[] {
  const out: SignalSite[] = [];
  for (const read of reads) {
    let at = read.end - read.name.length;
    if (declarations.some((d) => d.start <= at && read.end <= d.end)) continue;
    let path = "";
    for (const part of read.name.split(".")) {
      path = path ? `${path}.${part}` : part;
      if (defined.has(path)) out.push({ start: at, end: at + part.length, signal: path });
      at += part.length + 1;
    }
  }
  for (const d of declarations) out.push({ start: d.start, end: d.end, signal: d.name, declaration: d });
  return out;
}

/** The site at `offset`, its end included, as a caret just after a name stands on it. */
export function siteAt(sites: readonly SignalSite[], offset: number): SignalSite | null {
  return sites.find((s) => s.start <= offset && offset <= s.end) ?? null;
}

/** Does `site` name the signal `name`, or, `under` given, define one under it, which a rename moves with it? */
export function namesSignal(site: SignalSite, name: string, under = false): boolean {
  return site.signal === name || (under && site.declaration !== undefined && site.signal.startsWith(`${name}.`));
}

/**
 * Why the signal at `site` cannot be renamed from there, or null. A read of a name with a hyphen
 * is not found, so a rename would leave them stale; a property of a signals class is Kotlin's.
 */
export function renameRefusal(site: SignalSite): string | null {
  if (site.signal.includes("-")) return `A rename does not find the reads of '${site.signal}', whose hyphen no $name reaches.`;
  if (site.declaration?.kind === "property") return `'${site.signal}' is a property of a signals class: rename it with Kotlin's rename.`;
  return null;
}

/**
 * What goes over `site` in `text` when the signal `old` is renamed `newName`, or null when nothing
 * written there can name it. A key inside the object renamed is written without the object's name
 * and stays as it is; any other place that defines a signal under `old` moves with it.
 */
export function siteRenameText(text: string, site: SignalSite, old: string, newName: string): string | null {
  const d = site.declaration;
  if (!d) return signalReadRenameText(site.signal, newName);
  if (d.name === old) return signalRenameText(text, d, newName);
  if (d.kind === "objectKey") return text.slice(d.start, d.end);
  return signalRenameText(text, d, newName + d.name.slice(old.length));
}
