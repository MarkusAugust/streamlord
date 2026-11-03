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
 * defining place.
 */
export function signalSites(reads: readonly SignalReference[], declarations: readonly SignalDeclaration[], defined: ReadonlySet<string>): SignalSite[] {
  const out: SignalSite[] = [];
  for (const read of reads) {
    let at = read.end - read.name.length;
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

/** Does `site` name the signal `name`, or define one under it, which a rename moves with it? */
export function namesSignal(site: SignalSite, name: string): boolean {
  return site.signal === name || (site.declaration !== undefined && site.signal.startsWith(`${name}.`));
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
