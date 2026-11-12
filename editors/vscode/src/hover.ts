import * as vscode from "vscode";
import { actionCallAt, actionDoc, attributeDoc, attributeNamed, attributeOfHelper, canCallDsl, catalog, expressionAtCaret, fetchOptionDoc, inForeignDslCall, inHtmlString } from "./analysis.ts";

/** Hover docs for Datastar attributes in HTML (files and Kotlin strings), DSL functions in Kotlin, and actions in strings. */
export class StreamlordHoverProvider implements vscode.HoverProvider {
  constructor(private readonly prefix: () => string) {}

  provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | null {
    const src = document.getText();
    const offset = document.offsetAt(position);
    const kotlin = document.languageId === "kotlin";
    // Whether a Kotlin file can call the DSL, by its imports; markup leaves it open.
    const withKotlin = !kotlin || canCallDsl(src);
    // An action, `@post`, anywhere in a string or attribute value, but not in a DSL-named call the file cannot make.
    const actionRange = document.getWordRangeAtPosition(position, /@[A-Za-z_][A-Za-z0-9_]*/);
    if (actionRange && !(kotlin && inForeignDslCall(src, offset))) {
      const doc = actionDoc(document.getText(actionRange).slice(1), withKotlin);
      if (doc) return new vscode.Hover(new vscode.MarkdownString(doc), actionRange);
    }
    const prefix = this.prefix();
    const option = this.option(document, position, prefix);
    if (option) return option;
    if (!kotlin || inHtmlString(src, offset)) {
      const range = document.getWordRangeAtPosition(position, /[A-Za-z][A-Za-z0-9_:.-]*/);
      if (!range) return null;
      const attribute = attributeNamed(document.getText(range).toLowerCase(), prefix);
      const doc = attribute && attributeDoc(attribute.name, prefix, withKotlin);
      return doc ? new vscode.Hover(new vscode.MarkdownString(doc), range) : null;
    }
    // The DSL's functions are documented where the file can call them.
    if (!withKotlin) return null;
    const range = document.getWordRangeAtPosition(position, /[A-Za-z_][A-Za-z0-9_]*/);
    if (!range) return null;
    const word = document.getText(range);
    const attr = attributeOfHelper(word);
    const attrDoc = attr && attributeDoc(attr.name, prefix, true);
    if (attrDoc) return new vscode.Hover(new vscode.MarkdownString(attrDoc), range);
    const action = catalog().actions.find((a) => a.kotlin === word);
    const called = /\(/.test(document.lineAt(position.line).text.slice(range.end.character, range.end.character + 2));
    const doc = action && called ? actionDoc(action.name, false) : null;
    return doc ? new vscode.Hover(new vscode.MarkdownString(doc), range) : null;
  }

  /** A key of a backend action's options object: the word at the caret, read as the key it is when the caret stands at its end. */
  private option(document: vscode.TextDocument, position: vscode.Position, prefix: string): vscode.Hover | null {
    const range = document.getWordRangeAtPosition(position, /[A-Za-z_$][\w$]*/);
    if (!range) return null;
    const word = document.getText(range);
    const doc = fetchOptionDoc(word);
    if (!doc) return null;
    const expression = expressionAtCaret(document.getText(), document.offsetAt(range.end), document.languageId === "kotlin", prefix);
    if (!expression?.allowActions || actionCallAt(expression.before, expression.before.length)?.option?.typed !== word) return null;
    return new vscode.Hover(new vscode.MarkdownString(doc), range);
  }
}
