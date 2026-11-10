import * as vscode from "vscode";
import { lexCached } from "./analyze.ts";
import { expressionAtCaret, tagBeforeCaretInString, type ExpressionAtCaret } from "./caret.ts";
import { catalog, parseAttributeName, type AttributeSpec } from "./catalog.ts";
import { actionCallAt, tagBeforeCaret } from "./completionContext.ts";
import { actionPrefixAt, signalPrefixAt } from "./expression.ts";
import { keyKind } from "./markup.ts";
import { kotlinExpressionAtCaret, kotlinHtmlStringAt, kotlinModules, streamlordSites } from "./modules.ts";
import type { KotlinString } from "./kotlinStrings.ts";
import { findCallSites } from "./scanner.ts";
import type { SignalIndex } from "./signalIndex.ts";

export { stringAt } from "./scanner.ts";

const ALL_SITES: ReadonlySet<string> = new Set([
  ...Object.keys(catalog.callSites.expression),
  ...Object.keys(catalog.callSites.html),
  ...Object.keys(catalog.callSites.script),
  ...Object.keys(catalog.callSites.selector),
]);
const SELECTOR_ARG_NAMES = new Set(["selector", "viewTransitionSelector"]);

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
    const modules = kotlinModules(src);
    const selectorItems = this.selectorItems(src, offset, document, position, streamlordSites(ALL_SITES, modules));
    if (selectorItems) return selectorItems;
    const expression = kotlinExpressionAtCaret(src, offset, this.prefix(), modules);
    if (expression) return this.expressionItems(expression, src, offset, document, position);
    // HTML in a string, handed to patchElements or free-standing: the HTML side applies.
    const html = kotlinHtmlStringAt(src, offset, modules);
    if (html && offset > html.contentStart && offset <= html.contentEnd) return this.html(src, offset, document, position, html);
    return [];
  }

  /** `selector = "#|"` on any DSL call, or the first argument of removeElements: ids and classes from the workspace. */
  private selectorItems(
    src: string,
    offset: number,
    document: vscode.TextDocument,
    position: vscode.Position,
    sites: ReadonlySet<string>,
  ): vscode.CompletionItem[] | null {
    if (sites.size === 0) return null;
    const site = findCallSites(src, sites, lexCached(src).mask).find((s) => s.openParen < offset && offset <= s.closeParen);
    if (!site) return null;
    const arg = site.args.find((a) => a.string && a.string.start < offset && offset <= a.string.contentEnd + (a.string.unterminated ? 1 : 0));
    if (!arg?.string) return null;
    const isSelector = (arg.named !== null && SELECTOR_ARG_NAMES.has(arg.named)) || (arg.named === null && site.name === "removeElements" && site.args.indexOf(arg) === 0);
    if (!isSelector) return null;
    const typed = src.slice(arg.string.contentStart, offset);
    const token = /[#.]?[\w-]*$/.exec(typed)?.[0] ?? "";
    const local = this.signals.selectorsForFile(document.uri.toString());
    const all = this.signals.allSelectors();
    const range = new vscode.Range(position.translate(0, -token.length), position);
    const items: vscode.CompletionItem[] = [];
    const wantIds = !token.startsWith(".");
    const wantClasses = !token.startsWith("#");
    if (wantIds) {
      for (const id of [...all.ids].sort()) {
        const item = new vscode.CompletionItem("#" + id, vscode.CompletionItemKind.Reference);
        const isLocal = local.ids.has(id);
        item.detail = isLocal ? "id (this file)" : "id (workspace)";
        item.sortText = (isLocal ? "0" : "1") + id;
        item.range = range;
        items.push(item);
      }
    }
    if (wantClasses) {
      for (const cls of [...all.classes].sort()) {
        const item = new vscode.CompletionItem("." + cls, vscode.CompletionItemKind.Color);
        const isLocal = local.classes.has(cls);
        item.detail = isLocal ? "class (this file)" : "class (workspace)";
        item.sortText = (isLocal ? "2" : "3") + cls;
        item.range = range;
        items.push(item);
      }
    }
    return items;
  }

  /** Signals, actions, attribute names (outside plain HTML), modifiers and modifier values in markup languages. */
  /** `string` is the HTML string in Kotlin the caret is in, read decoded; without it `src` is markup. */
  private html(src: string, offset: number, document: vscode.TextDocument, position: vscode.Position, string?: KotlinString): vscode.CompletionItem[] {
    const prefix = this.prefix();
    // Inside an attribute value, read as the browser reads the markup: over several lines and in either quote.
    if (document.languageId !== "kotlin") {
      const expression = expressionAtCaret(src, offset, false, prefix);
      if (expression) return this.expressionItems(expression, src, offset, document, position);
    }
    // From the start of the tag the caret is in, which may be lines above, read as the browser reads the markup.
    const before = string ? tagBeforeCaretInString(string, offset, prefix) : tagBeforeCaret(src, offset, prefix);
    if (before === null) return [];
    // On an attribute name inside a tag: <div data-on:cl|
    // What comes before is whitespace-separated runs of plain characters and quoted strings, each
    // character read one way only. Telling names from values instead made `<a != != …` backtrack
    // exponentially, and anything stricter than this loses template code and Kotlin's \" quotes.
    const inName = /<([A-Za-z][A-Za-z0-9:-]*)(?:\s+(?:[^\s<>"']|"[^"]*"|'[^']*')+)*\s+([^\s<>="']*)$/.exec(before);
    if (!inName) return [];
    const partial = inName[2] ?? "";
    const tagName = (inName[1] ?? "").toLowerCase();
    if (partial.includes("__")) return this.modifierItems(partial, prefix, document, position);
    // The event of data-on, which VS Code's HTML service knows nothing of, in any file.
    if (partial.toLowerCase().startsWith(`${prefix}on:`)) return this.eventItems(partial.slice(prefix.length + 3), src, offset, position);
    if (!partial.startsWith(prefix.slice(0, Math.max(1, partial.length)))) return [];
    // In plain HTML files the attribute names come from html-customdata.json through VS Code's own
    // HTML service, with the same docs; offering them here as well would list them twice. Inside
    // Kotlin strings and template languages that service is absent.
    if (document.languageId === "html" && prefix === catalog.prefix) return [];
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
    return [...parsed.spec.modifiers].sort((x, y) => byName(x.name, y.name)).map((m) => {
      const item = new vscode.CompletionItem(m.name, vscode.CompletionItemKind.Keyword);
      item.sortText = m.name;
      item.detail = `__${m.name}` + (m.type === "flag" ? "" : m.type === "duration" ? ".<duration>" : m.type === "enum" ? `.<${(m.values ?? []).join("|")}>` : m.type === "int" ? ".<n>" : ".<name>");
      item.range = new vscode.Range(position.translate(0, -current.length), position);
      if (m.type === "duration") item.insertText = new vscode.SnippetString(`${m.name}.\${1:500ms}`);
      if (m.type === "enum") item.insertText = new vscode.SnippetString(`${m.name}.\${1|${(m.values ?? []).join(",")}|}`);
      if (m.type === "int") item.insertText = new vscode.SnippetString(`${m.name}.\${1:50}`);
      if (m.type === "ident" || m.type === "idents") item.insertText = new vscode.SnippetString(`${m.name}.\${1:name}`);
      return item;
    });
  }

  /**
   * Signals after `$`, actions after `@`, the options of a backend action on a key of its options
   * object, the scope names after a bare lowercase word. `patch` is a name only in
   * `data-on-signal-patch`, the one attribute the client hands it to. What is replaced is measured
   * back from the caret in the source, where the text before it may be written with escapes.
   */
  private expressionItems(expression: ExpressionAtCaret, src: string, offset: number, document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem[] {
    const textBefore = expression.before;
    const sig = signalPrefixAt(textBefore, textBefore.length);
    if (sig !== null) {
      const local = this.signals.forFile(document.uri.toString());
      // The dollar is replaced too where the source writes it plainly, and left as written otherwise (\$, ${'$'}).
      const dollar = src[offset - sig.length - 1] === "$" ? "$" : "";
      return [...this.signals.all()].sort().map((name) => {
        const item = new vscode.CompletionItem("$" + name, vscode.CompletionItemKind.Variable);
        const isLocal = local.has(name);
        item.detail = isLocal ? "signal (this file)" : "signal (workspace)";
        item.sortText = (isLocal ? "0" : "1") + name;
        item.range = new vscode.Range(document.positionAt(offset - sig.length - dollar.length), position);
        item.insertText = dollar + name;
        item.filterText = dollar + name;
        return item;
      });
    }
    if (!expression.allowActions) return [];
    const call = actionCallAt(textBefore, textBefore.length);
    if (call?.option) {
      const typed = call.option.typed;
      // The colon is written only where none follows yet.
      const colon = /^\s*:/.test(src.slice(offset)) ? "" : ": ";
      return catalog.fetchOptions
        .filter((o) => !call.option?.written.includes(o.name))
        .map((o) => {
          const item = new vscode.CompletionItem(o.name, vscode.CompletionItemKind.Property);
          item.detail = o.type;
          item.documentation = new vscode.MarkdownString(fetchOptionDoc(o));
          item.range = new vscode.Range(document.positionAt(offset - typed.length), position);
          item.insertText = o.name + colon;
          item.sortText = o.name;
          return item;
        });
    }
    const act = actionPrefixAt(textBefore, textBefore.length);
    if (act !== null) {
      return [...catalog.actions].sort((x, y) => Number(x.pro) - Number(y.pro) || byName(x.name, y.name)).map((a) => {
        const item = new vscode.CompletionItem("@" + a.name, vscode.CompletionItemKind.Function);
        item.detail = (a.pro ? "Pro · " : "") + a.signature;
        item.documentation = a.doc;
        item.range = new vscode.Range(document.positionAt(offset - act.length - 1), position);
        item.insertText = new vscode.SnippetString("@" + a.name + (a.kind === "backend" ? "('${1:/path}')" : a.name === "peek" ? "(() => ${1})" : "(${1})"));
        item.sortText = (a.pro ? "1" : "0") + a.name;
        return item;
      });
    }
    const scope = /(?:^|[^A-Za-z0-9_$])([a-z]*)$/.exec(textBefore);
    if (scope && (scope[1] ?? "").length > 0) {
      const compiled = catalog.compiledParameters.filter((n) => /^[a-z]/.test(n));
      return [...compiled, ...(expression.patchInScope ? ["patch"] : [])].map((v) => new vscode.CompletionItem(v, vscode.CompletionItemKind.Variable));
    }
    return [];
  }

  /** The event of `data-on:`, Datastar's own first, then the DOM's; `typed` is what is written of it. */
  private eventItems(typed: string, src: string, offset: number, position: vscode.Position): vscode.CompletionItem[] {
    const range = new vscode.Range(position.translate(0, -typed.length), position);
    // A value is opened only where none follows yet.
    const value = /^\s*=/.test(src.slice(offset)) ? "" : '="${1}"';
    const datastar = catalog.datastarEvents.map((e) => {
      const item = new vscode.CompletionItem(e.name, vscode.CompletionItemKind.Event);
      item.detail = "Datastar event";
      item.documentation = new vscode.MarkdownString(e.doc);
      item.sortText = "0" + e.name;
      return item;
    });
    const dom = catalog.domEvents.map((e) => {
      const item = new vscode.CompletionItem(e.name, vscode.CompletionItemKind.Event);
      item.detail = "DOM event";
      if (e.mdn) item.documentation = new vscode.MarkdownString(`[MDN](${e.mdn})`);
      item.sortText = "1" + e.name;
      return item;
    });
    return [...datastar, ...dom].map((item) => {
      item.range = range;
      item.insertText = new vscode.SnippetString(String(item.label) + value);
      return item;
    });
  }
}

/** attributeDoc in Documentation.kt: `withKotlin` leaves the helpers out, for a project without streamlord-html. */
export function docFor(a: AttributeSpec, prefix: string, withKotlin = true): string {
  const mods = a.modifiers.length ? "\n\n**Modifiers:** " + a.modifiers.map((m) => "`__" + m.name + (m.type === "flag" ? "" : m.type === "enum" ? "." + (m.values ?? []).join("|") : m.type === "duration" ? ".500ms" : m.type === "int" ? ".n" : ".name") + "`").join(", ") : "";
  const kotlin = withKotlin && a.kotlin.length ? "\n\n**Kotlin:** " + a.kotlin.map((k) => "`" + k + "()`").join(", ") : "";
  const pro = a.pro ? "\n\n_Datastar Pro. Requires the Pro bundle, which you license and load yourself._" : "";
  const casing = a.keyed ? "\n\n**Key casing:** " + casingNote(a) : "";
  return `**${prefix}${a.name}**\n\n${a.doc}\n\n${a.forms.map((f) => "`" + f.replace(/^data-/, prefix) + "`").join("  \n")}${casing}${mods}${kotlin}${pro}`;
}

/** actionDoc in Documentation.kt: an action, its signature and, unless `withKotlin` is false, its Kotlin helper. */
export function actionDoc(a: (typeof catalog.actions)[number], withKotlin = true): string {
  return `**${a.signature}**\n\n${a.doc}${a.pro ? "\n\n_Datastar Pro._" : ""}${withKotlin ? `\n\nKotlin: \`${a.kotlin}()\`` : ""}`;
}

/** fetchOptionDoc in Documentation.kt: an option of the backend actions, its type, its default and what it does. */
export function fetchOptionDoc(o: (typeof catalog.fetchOptions)[number]): string {
  return `**${o.name}**: \`${o.type}\`, default \`${o.default}\`\n\n${o.doc ?? ""}`;
}

/** The browser lowercases attribute names; how Datastar reads the key differs per attribute. */
function casingNote(a: AttributeSpec): string {
  switch (keyKind(a)) {
    case "signal":
      return "the browser lowercases attribute names, and Datastar reads the key as camelCase: `foo-bar` is the signal `$fooBar`. Write keys in kebab-case; `__case` changes the conversion.";
    case "event":
      return "the key is the event name, lowercased by the browser. For a camelCase event write `widget-loaded__case.camel`.";
    case "class":
      return "the key is the class name, lowercased by the browser. For a camelCase class write `is-open__case.camel`.";
    default:
      return "the key is used as written, after the browser has lowercased it. Write it in kebab-case; for a name that really has capitals (SVG's `viewBox`), use the object form.";
  }
}

/**
 * Actions and modifiers are offered by name, Pro after the rest, as the IntelliJ plugin orders
 * them; sortText carries the order, since VS Code sorts by it.
 */
function byName(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
