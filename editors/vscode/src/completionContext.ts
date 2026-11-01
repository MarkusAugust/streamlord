/**
 * Where a caret stands, for completion, hover and signature help: the twin of CompletionContext.kt
 * in streamlord-analysis, both held to test/fixtures/twins/completion-contexts.json.
 */

/** The attribute value a caret stands in: the attribute's name as written, where its value starts, and the value before the caret, decoded. */
export interface ValueAtCaret {
  attribute: string;
  valueStart: number;
  before: string;
}

/** The quoted attribute value `offset` stands in, in `markup` read as the browser reads it, or null. */
export function valueAtCaret(_markup: string, _offset: number, _prefix: string): ValueAtCaret | null {
  return null;
}

/** The action call a caret stands in: the action's name, which argument, and the key of the options object when the caret is on one. */
export interface ActionCall {
  action: string;
  argument: number;
  option: OptionKeyAt | null;
}

/** A key of a backend action's options object at the caret: what is typed of it, and the keys the object already holds. */
export interface OptionKeyAt {
  typed: string;
  written: string[];
}

/** The innermost `@name(` still open at `offset` in the expression `text`, read from the text before the caret, or null. */
export function actionCallAt(_text: string, _offset: number): ActionCall | null {
  return null;
}
