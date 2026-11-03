import * as vscode from "vscode";
import { INDEXED_SCHEMES, placesIn, textOf, type Places, type SignalIndex } from "./signalIndex.ts";
import { namesSignal, renameRefusal, signalSites, siteAt, siteRenameText, type SignalSite } from "./signalSites.ts";

/** The index as the providers use it: what the workspace defines, and where each file names signals. */
type Index = Pick<SignalIndex, "allDefinitions" | "allPlaces">;

/**
 * Go to definition, find references and rename for signals, in Kotlin and in markup: from a read
 * or from a defining place, to every place the workspace defines or reads the signal.
 */
export class SignalNavigationProvider implements vscode.DefinitionProvider, vscode.ReferenceProvider, vscode.RenameProvider {
  constructor(
    private readonly index: Index,
    private readonly prefix: () => string,
  ) {}

  async provideDefinition(document: vscode.TextDocument, position: vscode.Position): Promise<vscode.Location[] | null> {
    const site = this.siteAt(document, position);
    if (!site) return null;
    return (await this.sitesOf(site.signal)).filter(({ site: s }) => s.declaration && s.signal === site.signal).map(location);
  }

  async provideReferences(document: vscode.TextDocument, position: vscode.Position, context: vscode.ReferenceContext): Promise<vscode.Location[] | null> {
    const site = this.siteAt(document, position);
    if (!site) return null;
    return (await this.sitesOf(site.signal)).filter(({ site: s }) => context.includeDeclaration || !s.declaration).map(location);
  }

  prepareRename(document: vscode.TextDocument, position: vscode.Position): { range: vscode.Range; placeholder: string } {
    const site = this.siteAt(document, position);
    if (!site) throw new Error("No signal here to rename.");
    const refusal = renameRefusal(site);
    if (refusal) throw new Error(refusal);
    return { range: new vscode.Range(document.positionAt(site.start), document.positionAt(site.end)), placeholder: site.signal };
  }

  /**
   * Every place the workspace defines or reads the signal, each written as its place wants. A place
   * that cannot hold the new name, or a name the workspace already defines, stops the rename before
   * anything is written.
   */
  async provideRenameEdits(document: vscode.TextDocument, position: vscode.Position, newName: string): Promise<vscode.WorkspaceEdit> {
    const site = this.siteAt(document, position);
    if (!site) throw new Error("No signal here to rename.");
    const refusal = renameRefusal(site);
    if (refusal) throw new Error(refusal);
    const old = site.signal;
    if (newName !== old && this.index.allDefinitions().has(newName)) throw new Error(`The workspace already defines the signal '${newName}'.`);
    const edit = new vscode.WorkspaceEdit();
    const byFile = new Map<string, Found[]>();
    for (const found of await this.sitesOf(old, true)) byFile.set(found.uri, [...(byFile.get(found.uri) ?? []), found]);
    for (const [uri, sites] of byFile) {
      const text = await textOf(uri);
      if (text === null) continue;
      for (const { site: s, places } of sites) {
        const written = siteRenameText(text, s, old, newName);
        const file = uri.slice(uri.lastIndexOf("/") + 1);
        if (written === null) throw new Error(`'${text.slice(s.start, s.end)}' in ${file} cannot be written so that it names '${newName}'.`);
        edit.replace(vscode.Uri.parse(uri), rangeIn(places, s), written);
      }
    }
    return edit;
  }

  private siteAt(document: vscode.TextDocument, position: vscode.Position): SignalSite | null {
    // A git diff or an untitled buffer is no file of the workspace, which is what a rename edits.
    if (!INDEXED_SCHEMES.has(document.uri.scheme)) return null;
    const language = document.languageId === "kotlin" ? "kotlin" : "html";
    const places = placesIn(document.getText(), language, this.prefix());
    return siteAt(signalSites(places.reads, places.declarations, this.index.allDefinitions()), document.offsetAt(position));
  }

  /** Every site in the workspace that names `name`, and with `under` every one that defines a signal under it. */
  private async sitesOf(name: string, under = false): Promise<Found[]> {
    const defined = this.index.allDefinitions();
    const out: Found[] = [];
    for (const [uri, places] of await this.index.allPlaces()) {
      for (const site of signalSites(places.reads, places.declarations, defined)) if (namesSignal(site, name, under)) out.push({ uri, site, places });
    }
    return out;
  }
}

interface Found {
  uri: string;
  site: SignalSite;
  places: Places;
}

function location({ uri, site, places }: Found): vscode.Location {
  return new vscode.Location(vscode.Uri.parse(uri), rangeIn(places, site));
}

function rangeIn(places: Places, site: SignalSite): vscode.Range {
  return new vscode.Range(positionIn(places, site.start), positionIn(places, site.end));
}

function positionIn(places: Places, offset: number): vscode.Position {
  let lo = 0;
  let hi = places.lineStarts.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (places.lineStarts[mid]! <= offset) lo = mid;
    else hi = mid - 1;
  }
  return new vscode.Position(lo, offset - places.lineStarts[lo]!);
}
