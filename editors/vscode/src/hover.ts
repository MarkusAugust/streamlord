import * as vscode from "vscode";
import { expressionAtCaret } from "./caret.ts";
import { catalog, parseAttributeName } from "./catalog.ts";
import { actionDoc, docFor, fetchOptionDoc } from "./completion.ts";
import { actionCallAt } from "./completionContext.ts";
import { inForeignDslCall, kotlinExpressionAtCaret, kotlinHtmlStringAt, kotlinModules } from "./modules.ts";

/** Hover docs for Datastar attributes in HTML (files and Kotlin strings), DSL functions in Kotlin, and actions in strings. */
export class StreamlordHoverProvider implements vscode.HoverProvider {
  constructor(private readonly prefix: () => string) {}

  provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | null {
    const src = document.getText();
    const offset = document.offsetAt(position);
    const kotlin = document.languageId === "kotlin";
    // A Kotlin file's modules, by its imports and the build files; markup leaves them unknown.
    const modules = kotlin ? kotlinModules(src) : {};
    const withKotlin = modules.htmlDsl !== false;
    // An action, `@post`, anywhere in a string or attribute value, but not in a DSL-named call the file cannot make.
    const actionRange = document.getWordRangeAtPosition(position, /@[A-Za-z_][A-Za-z0-9_]*/);
    if (actionRange && !(kotlin && inForeignDslCall(src, offset, modules))) {
      const name = document.getText(actionRange).slice(1);
      const a = catalog.actionsByName.get(name);
      if (a) return new vscode.Hover(new vscode.MarkdownString(actionDoc(a, withKotlin)), actionRange);
    }
    const prefix = this.prefix();
    const option = this.option(document, position, prefix, modules);
    if (option) return option;
    if (!kotlin || kotlinHtmlStringAt(src, offset, modules)) {
      const range = document.getWordRangeAtPosition(position, /[A-Za-z][A-Za-z0-9_:.-]*/);
      if (!range) return null;
      const parsed = parseAttributeName(document.getText(range).toLowerCase(), prefix);
      if (parsed?.spec) return new vscode.Hover(new vscode.MarkdownString(docFor(parsed.spec, prefix, withKotlin)), range);
      return null;
    }
    // The DSL's functions are documented where the file can call them.
    if (modules.htmlDsl === false) return null;
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
  private option(
    document: vscode.TextDocument,
    position: vscode.Position,
    prefix: string,
    modules: ReturnType<typeof kotlinModules>,
  ): vscode.Hover | null {
    const range = document.getWordRangeAtPosition(position, /[A-Za-z_$][\w$]*/);
    if (!range) return null;
    const word = document.getText(range);
    const o = catalog.fetchOptions.find((x) => x.name === word);
    if (!o) return null;
    const src = document.getText();
    const end = document.offsetAt(range.end);
    const expression =
      document.languageId === "kotlin" ? kotlinExpressionAtCaret(src, end, prefix, modules) : expressionAtCaret(src, end, false, prefix);
    if (!expression?.allowActions || actionCallAt(expression.before, expression.before.length)?.option?.typed !== word) return null;
    return new vscode.Hover(new vscode.MarkdownString(fetchOptionDoc(o)), range);
  }
}
