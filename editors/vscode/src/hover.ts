import * as vscode from "vscode";
import { catalog, parseAttributeName } from "./catalog.ts";
import { docFor } from "./completion.ts";

/** Hover docs for Datastar attributes in HTML, DSL functions in Kotlin, and actions in strings. */
export class StreamlordHoverProvider implements vscode.HoverProvider {
  constructor(private readonly prefix: () => string) {}

  provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | null {
    const actionRange = document.getWordRangeAtPosition(position, /@[A-Za-z_][A-Za-z0-9_]*/);
    if (actionRange) {
      const name = document.getText(actionRange).slice(1);
      const a = catalog.actionsByName.get(name);
      if (a) return new vscode.Hover(new vscode.MarkdownString(`**${a.signature}**\n\n${a.doc}${a.pro ? "\n\n_Datastar Pro._" : ""}\n\nKotlin: \`${a.kotlin}()\``), actionRange);
    }
    const prefix = this.prefix();
    if (document.languageId !== "kotlin") {
      const range = document.getWordRangeAtPosition(position, /[A-Za-z][A-Za-z0-9_:.-]*/);
      if (!range) return null;
      const parsed = parseAttributeName(document.getText(range).toLowerCase(), prefix);
      if (parsed?.spec) return new vscode.Hover(new vscode.MarkdownString(docFor(parsed.spec, prefix)), range);
      return null;
    }
    const range = document.getWordRangeAtPosition(position, /[A-Za-z_][A-Za-z0-9_]*/);
    if (!range) return null;
    const word = document.getText(range);
    const attr = catalog.attributesByKotlin.get(word);
    if (attr) return new vscode.Hover(new vscode.MarkdownString(docFor(attr, prefix)), range);
    const action = catalog.actions.find((a) => a.kotlin === word);
    if (action && /\(/.test(document.lineAt(position.line).text.slice(range.end.character, range.end.character + 2))) {
      return new vscode.Hover(new vscode.MarkdownString(`**${action.signature}**\n\n${action.doc}${action.pro ? "\n\n_Datastar Pro._" : ""}`), range);
    }
    return null;
  }
}
