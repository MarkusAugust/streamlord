/**
 * Just enough of the `vscode` module to run the completion and hover providers under node:test.
 * Loaded through the resolve hook in `test/hooks.mjs`.
 */

export class Position {
  readonly line: number;
  readonly character: number;
  constructor(line: number, character: number) {
    this.line = line;
    this.character = character;
  }
  translate(lineDelta = 0, characterDelta = 0): Position {
    return new Position(this.line + lineDelta, this.character + characterDelta);
  }
}

export class Range {
  readonly start: Position;
  readonly end: Position;
  constructor(start: Position, end: Position) {
    this.start = start;
    this.end = end;
  }
}

export const CompletionItemKind = {
  Text: 0, Method: 1, Function: 2, Constructor: 3, Field: 4, Variable: 5, Class: 6, Interface: 7, Module: 8, Property: 9,
  Unit: 10, Value: 11, Enum: 12, Keyword: 13, Snippet: 14, Color: 15, File: 16, Reference: 17, Folder: 18, EnumMember: 19,
  Constant: 20, Struct: 21, Event: 22, Operator: 23, TypeParameter: 24,
} as const;

export class CompletionItem {
  label: string;
  kind?: number;
  detail?: string;
  documentation?: unknown;
  range?: Range;
  insertText?: unknown;
  sortText?: string;
  filterText?: string;
  constructor(label: string, kind?: number) {
    this.label = label;
    this.kind = kind;
  }
}

export class SnippetString {
  value: string;
  constructor(value: string) {
    this.value = value;
  }
}

export class MarkdownString {
  value: string;
  constructor(value: string) {
    this.value = value;
  }
}

export class Hover {
  contents: unknown;
  range?: Range;
  constructor(contents: unknown, range?: Range) {
    this.contents = contents;
    this.range = range;
  }
}

export class Disposable {
  private readonly fn: () => void;
  constructor(fn: () => void = () => {}) {
    this.fn = fn;
  }
  dispose(): void {
    this.fn();
  }
}

export const ConfigurationTarget = { Global: 1, Workspace: 2, WorkspaceFolder: 3 } as const;

const noop = () => new Disposable();

export const workspace = {
  onDidSaveTextDocument: noop,
  onDidChangeTextDocument: noop,
  onDidDeleteFiles: noop,
  findFiles: async () => [],
  fs: { readFile: async () => new Uint8Array() },
  getConfiguration: () => ({ get: <T>(_k: string, d: T) => d, update: async () => {} }),
};

export const window = { showInformationMessage: () => {} };

/** A TextDocument over a string, with the two lookups the providers use. */
export class MockDocument {
  private readonly lineStarts: number[] = [0];
  readonly text: string;
  readonly languageId: string;
  readonly uri: { toString: () => string; path: string };
  constructor(text: string, languageId: string) {
    this.text = text;
    this.languageId = languageId;
    this.uri = { toString: () => `file:///mock.${languageId}`, path: `/mock.${languageId}` };
    for (let i = 0; i < text.length; i++) if (text[i] === "\n") this.lineStarts.push(i + 1);
  }
  getText(range?: Range): string {
    if (!range) return this.text;
    return this.text.slice(this.offsetAt(range.start), this.offsetAt(range.end));
  }
  offsetAt(p: Position): number {
    return (this.lineStarts[p.line] ?? 0) + p.character;
  }
  positionAt(offset: number): Position {
    let line = 0;
    while (line + 1 < this.lineStarts.length && (this.lineStarts[line + 1] ?? 0) <= offset) line++;
    return new Position(line, offset - (this.lineStarts[line] ?? 0));
  }
  lineAt(line: number) {
    const start = this.lineStarts[line] ?? 0;
    const end = this.lineStarts[line + 1] !== undefined ? (this.lineStarts[line + 1] ?? 0) - 1 : this.text.length;
    return { text: this.text.slice(start, end) };
  }
  /** Word range using the given regex, like VS Code does. */
  getWordRangeAtPosition(p: Position, re: RegExp): Range | undefined {
    const lineText = this.lineAt(p.line).text;
    const g = new RegExp(re.source, re.flags.includes("g") ? re.flags : re.flags + "g");
    let m: RegExpExecArray | null;
    while ((m = g.exec(lineText)) !== null) {
      if (m.index <= p.character && p.character <= m.index + m[0].length) {
        return new Range(new Position(p.line, m.index), new Position(p.line, m.index + m[0].length));
      }
      if (m[0].length === 0) g.lastIndex++;
    }
    return undefined;
  }
}
