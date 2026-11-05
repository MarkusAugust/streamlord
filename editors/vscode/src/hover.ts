import * as vscode from "vscode";
import { htmlStringAt } from "./analyze.ts";
import { expressionAtCaret } from "./caret.ts";
import { catalog, parseAttributeName } from "./catalog.ts";
import { actionDoc, docFor, fetchOptionDoc } from "./completion.ts";
import { actionCallAt } from "./completionContext.ts";

/** Hover docs for Datastar attributes in HTML (files and Kotlin strings), DSL functions in Kotlin, and actions in strings. */
export class StreamlordHoverProvider implements vscode.HoverProvider {
  constructor(private readonly prefix: () => string) {}

  provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | null {
    const actionRange = document.getWordRangeAtPosition(position, /@[A-Za-z_][A-Za-z0-9_]*/);
    if (actionRange) {
      const name = document.getText(actionRange).slice(1);
      const a = catalog.actionsByName.get(name);
      if (a) return new vscode.Hover(new vscode.MarkdownString(actionDoc(a)), actionRange);
    }
    const prefix = this.prefix();
    const option = this.option(document, position, prefix);
    if (option) return option;
    if (document.languageId !== "kotlin" || htmlStringAt(document.getText(), document.offsetAt(position))) {
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
      return new vscode.Hover(new vscode.MarkdownString(actionDoc(action, false)), range);
    }
    return null;
  }

  /** A key of a backend action's options object: the word at the caret, read as the key it is when the caret stands at its end. */
  private option(document: vscode.TextDocument, position: vscode.Position, prefix: string): vscode.Hover | null {
    const range = document.getWordRangeAtPosition(position, /[A-Za-z_$][\w$]*/);
    if (!range) return null;
    const word = document.getText(range);
    const o = catalog.fetchOptions.find((x) => x.name === word);
    if (!o) return null;
    const expression = expressionAtCaret(document.getText(), document.offsetAt(range.end), document.languageId === "kotlin", prefix);
    if (!expression?.allowActions || actionCallAt(expression.before, expression.before.length)?.option?.typed !== word) return null;
    return new vscode.Hover(new vscode.MarkdownString(fetchOptionDoc(o)), range);
  }
}
