import * as vscode from "vscode";
import { complete, defaultPrefix, type CompletionItem } from "./analysis.ts";
import type { SignalIndex } from "./signalIndex.ts";

const KINDS: Record<string, vscode.CompletionItemKind> = {
  signal: vscode.CompletionItemKind.Variable,
  scope: vscode.CompletionItemKind.Variable,
  action: vscode.CompletionItemKind.Function,
  option: vscode.CompletionItemKind.Property,
  attribute: vscode.CompletionItemKind.Property,
  event: vscode.CompletionItemKind.Event,
  modifier: vscode.CompletionItemKind.Keyword,
  value: vscode.CompletionItemKind.EnumMember,
  id: vscode.CompletionItemKind.Reference,
  class: vscode.CompletionItemKind.Color,
};

/** Completions for Datastar in markup, in Kotlin strings, in the DSL and its attributes form: the analysis's items, as VS Code shows them. */
export class StreamlordCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly signals: SignalIndex, private readonly prefix: () => string) {}

  provideCompletionItems(document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem[] {
    const prefix = this.prefix();
    const selectors = this.signals.allSelectors();
    // In plain HTML files VS Code's own HTML service offers the attribute names, from html-customdata.json, for the catalog's prefix.
    const names = !(document.languageId === "html" && prefix === defaultPrefix());
    const kotlin = document.languageId === "kotlin";
    return complete(document.getText(), document.offsetAt(position), kotlin, prefix, this.signals.allDefinitions(), [...selectors.ids], [...selectors.classes], names).map((i) => item(i, document, position));
  }
}

function item(i: CompletionItem, document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem {
  // A tail, an option's default, shows right after the name.
  const out = new vscode.CompletionItem(i.tail != null ? { label: i.label, detail: i.tail } : i.label, KINDS[i.kind]);
  out.range = new vscode.Range(document.positionAt(i.replaceFrom), position);
  if (i.snippet != null) out.insertText = new vscode.SnippetString(i.snippet);
  else if (i.insert != null) out.insertText = i.insert;
  out.detail = i.detail ?? undefined;
  if (i.doc != null) out.documentation = new vscode.MarkdownString(i.doc);
  out.filterText = i.filter ?? undefined;
  out.sortText = i.sort ?? undefined;
  return out;
}
