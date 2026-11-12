import * as vscode from "vscode";
import { hover } from "./analysis.ts";

/** Hover docs for Datastar attributes, actions, options and the DSL's functions, as the analysis has them. */
export class StreamlordHoverProvider implements vscode.HoverProvider {
  constructor(private readonly prefix: () => string) {}

  provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | null {
    const found = hover(document.getText(), document.offsetAt(position), document.languageId === "kotlin", this.prefix());
    if (!found) return null;
    return new vscode.Hover(new vscode.MarkdownString(found.markdown), new vscode.Range(document.positionAt(found.start), document.positionAt(found.end)));
  }
}
