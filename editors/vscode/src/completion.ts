import * as vscode from "vscode";
import { catalog, parseAttributeName, type AttributeSpec } from "./catalog.ts";
import { actionPrefixAt, signalPrefixAt } from "./expression.ts";
import { readKotlinStringAt } from "./kotlinStrings.ts";
import { findCallSites, selectStringArg } from "./scanner.ts";
import type { SignalIndex } from "./signalIndex.ts";

const EXPRESSION_SITES: ReadonlySet<string> = new Set(Object.keys(catalog.callSites.expression));

/** Completions for Datastar expressions in Kotlin strings, and for data-* attributes in HTML. */
export class StreamlordCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly signals: SignalIndex, private readonly prefix: () => string) {}

  provideCompletionItems(document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem[] {
    const src = document.getText();
    const offset = document.offsetAt(position);
    if (document.languageId === "kotlin") return this.kotlin(src, offset, document, position);
    return this.html(src, offset, document, position);
  }

  private kotlin(src: string, offset: number, document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem[] {
    const site = findCallSites(src, EXPRESSION_SITES).find((s) => s.openParen < offset && offset <= s.closeParen);
    if (!site) return [];
    const spec = catalog.callSites.expression[site.name];
    if (!spec) return [];
    const s = selectStringArg(site, spec.arg, spec.named) ?? site.args.map((a) => a.string).find((x) => x && x.start < offset && offset <= x.end) ?? null;
    if (!s || offset <= s.start || offset > s.contentEnd + (s.unterminated ? 1 : 0)) return [];
    const text = src.slice(s.contentStart, offset);
    return [...this.expressionItems(text, document, position, offset - text.length, true), ...this.modifierPropertyItems(src, offset, site.name)];
  }

  private html(src: string, offset: number, document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem[] {
    const prefix = this.prefix();
    const lineStart = src.lastIndexOf("\n", offset - 1) + 1;
    const before = src.slice(Math.max(lineStart, offset - 400), offset);
    // Inside an attribute value: name="...|
    const inValue = /([^\s"'<>=]+)\s*=\s*"([^"]*)$/.exec(before);
    if (inValue) {
      const parsed = parseAttributeName((inValue[1] ?? "").toLowerCase(), prefix);
      if (parsed?.spec && (parsed.spec.valueKind === "expression" || parsed.spec.valueKind === "signal")) {
        return this.expressionItems(inValue[2] ?? "", document, position, offset - (inValue[2] ?? "").length, parsed.spec.valueKind === "expression");
      }
      return [];
    }
    // On an attribute name inside a tag: <div data-on:cl|
    const inName = /<([A-Za-z][A-Za-z0-9:-]*)(?:\s+[^\s<>]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'>]*))?)*\s+([^\s<>="']*)$/.exec(before);
    if (!inName) return [];
    const partial = inName[2] ?? "";
    const tagName = (inName[1] ?? "").toLowerCase();
    if (partial.includes("__")) return this.modifierItems(partial, prefix, document, position);
    if (!partial.startsWith(prefix.slice(0, Math.max(1, partial.length)))) return [];
    return catalog.attributes
      .filter((a) => !a.onlyOn || a.onlyOn.includes(tagName))
      .map((a) => {
        const item = new vscode.CompletionItem(prefix + a.name, vscode.CompletionItemKind.Property);
        item.detail = (a.pro ? "Datastar Pro · " : "Datastar · ") + (a.forms[0] ?? "");
        item.documentation = new vscode.MarkdownString(docFor(a, prefix));
        item.range = new vscode.Range(position.translate(0, -partial.length), position);
        const needsKey = a.keyRequired;
        const needsValue = a.valueKind !== "none";
        item.insertText = new vscode.SnippetString(prefix + a.name + (needsKey ? ":${1:key}" : "") + (needsValue ? '="${2}"' : ""));
        item.sortText = (a.pro ? "1" : "0") + a.name;
        return item;
      });
  }

  private modifierItems(partial: string, prefix: string, document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem[] {
    const parsed = parseAttributeName(partial.toLowerCase(), prefix);
    if (!parsed?.spec) return [];
    const lastSep = partial.lastIndexOf("__");
    const current = partial.slice(lastSep + 2);
    const dot = current.indexOf(".");
    if (dot >= 0) {
      const mod = parsed.spec.modifiers.find((m) => m.name === current.slice(0, dot));
      if (!mod) return [];
      const afterDot = current.slice(current.lastIndexOf(".") + 1);
      const values = mod.type === "enum" ? (mod.values ?? []) : mod.type === "duration" ? (current.split(".").length > 2 ? (mod.flags ?? []) : ["500ms", "1s", "300ms", "100ms"]) : [];
      return values.map((v) => {
        const item = new vscode.CompletionItem(v, vscode.CompletionItemKind.EnumMember);
        item.range = new vscode.Range(position.translate(0, -afterDot.length), position);
        return item;
      });
    }
    return parsed.spec.modifiers.map((m) => {
      const item = new vscode.CompletionItem(m.name, vscode.CompletionItemKind.Keyword);
      item.detail = `__${m.name}` + (m.type === "flag" ? "" : m.type === "duration" ? ".<duration>" : m.type === "enum" ? `.<${(m.values ?? []).join("|")}>` : m.type === "int" ? ".<n>" : ".<name>");
      item.range = new vscode.Range(position.translate(0, -current.length), position);
      if (m.type === "duration") item.insertText = new vscode.SnippetString(`${m.name}.\${1:500ms}`);
      if (m.type === "enum") item.insertText = new vscode.SnippetString(`${m.name}.\${1|${(m.values ?? []).join(",")}|}`);
      if (m.type === "int") item.insertText = new vscode.SnippetString(`${m.name}.\${1:50}`);
      if (m.type === "ident" || m.type === "idents") item.insertText = new vscode.SnippetString(`${m.name}.\${1:name}`);
      return item;
    });
  }

  private expressionItems(textBefore: string, document: vscode.TextDocument, position: vscode.Position, textStartOffset: number, allowActions: boolean): vscode.CompletionItem[] {
    const sig = signalPrefixAt(textBefore, textBefore.length);
    if (sig !== null) {
      return [...this.signals.all()].sort().map((name) => {
        const item = new vscode.CompletionItem("$" + name, vscode.CompletionItemKind.Variable);
        item.detail = "signal";
        item.range = new vscode.Range(document.positionAt(textStartOffset + textBefore.length - sig.length - 1), position);
        item.filterText = "$" + name;
        return item;
      });
    }
    if (!allowActions) return [];
    const act = actionPrefixAt(textBefore, textBefore.length);
    if (act !== null) {
      return catalog.actions.map((a) => {
        const item = new vscode.CompletionItem("@" + a.name, vscode.CompletionItemKind.Function);
        item.detail = (a.pro ? "Pro · " : "") + a.signature;
        item.documentation = a.doc;
        item.range = new vscode.Range(document.positionAt(textStartOffset + textBefore.length - act.length - 1), position);
        item.insertText = new vscode.SnippetString("@" + a.name + (a.kind === "backend" ? "('${1:/path}')" : a.name === "peek" ? "(() => ${1})" : "(${1})"));
        item.sortText = (a.pro ? "1" : "0") + a.name;
        return item;
      });
    }
    const scope = /(?:^|[^A-Za-z0-9_$])([a-z]*)$/.exec(textBefore);
    if (scope && (scope[1] ?? "").length > 0) {
      return ["el", "evt", "patch"].map((v) => new vscode.CompletionItem(v, vscode.CompletionItemKind.Variable));
    }
    return [];
  }

  /** Inside the modifier lambda of a DSL call: `dataOnClick("...") { deb| }`. */
  private modifierPropertyItems(src: string, offset: number, fn: string): vscode.CompletionItem[] {
    void src;
    void offset;
    void fn;
    return [];
  }
}

export function docFor(a: AttributeSpec, prefix: string): string {
  const mods = a.modifiers.length ? "\n\n**Modifiers:** " + a.modifiers.map((m) => "`__" + m.name + (m.type === "flag" ? "" : m.type === "enum" ? "." + (m.values ?? []).join("|") : m.type === "duration" ? ".500ms" : m.type === "int" ? ".n" : ".name") + "`").join(", ") : "";
  const kotlin = a.kotlin.length ? "\n\n**Kotlin:** " + a.kotlin.map((k) => "`" + k + "()`").join(", ") : "";
  const pro = a.pro ? "\n\n_Datastar Pro. Requires the Pro bundle, which you license and load yourself._" : "";
  return `**${prefix}${a.name}**\n\n${a.doc}\n\n${a.forms.map((f) => "`" + f.replace(/^data-/, prefix) + "`").join("  \n")}${mods}${kotlin}${pro}`;
}

/** Find the Kotlin string literal that contains an offset, if any (used by hover). */
export function stringAt(src: string, offset: number): ReturnType<typeof readKotlinStringAt> {
  let i = src.lastIndexOf('"', offset - 1);
  while (i >= 0) {
    const s = readKotlinStringAt(src, i);
    if (s && s.start < offset && offset <= s.end) return s;
    i = src.lastIndexOf('"', i - 1);
  }
  return null;
}
