import * as vscode from "vscode";
import customData from "../html-customdata.json" with { type: "json" };
import { defaultPrefix, hover } from "./analysis.ts";

/** The attribute names VS Code's HTML service documents itself in an html file, from html-customdata.json. */
const documentedByHtml = new Set(customData.globalAttributes.map((a) => a.name));

/** Hover docs for Datastar attributes, actions, options and the DSL's functions, as the analysis has them. */
export class StreamlordHoverProvider implements vscode.HoverProvider {
  constructor(private readonly prefix: () => string) {}

  provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | null {
    const prefix = this.prefix();
    const found = hover(document.getText(), document.offsetAt(position), document.languageId === "kotlin", prefix);
    if (!found) return null;
    const range = new vscode.Range(document.positionAt(found.start), document.positionAt(found.end));
    // The HTML service shows the same documentation for a name that is exactly one of its custom data; a second hover would repeat it.
    if (document.languageId === "html" && prefix === defaultPrefix() && documentedByHtml.has(document.getText(range).toLowerCase())) return null;
    return new vscode.Hover(new vscode.MarkdownString(found.markdown), range);
  }
}
