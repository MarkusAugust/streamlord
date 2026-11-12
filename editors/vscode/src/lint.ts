import { diagnose, type DriftFacts, type Issue } from "./analysis.ts";

/**
 * What the analysis finds in the documents the editor has open, each known by `key`. One the
 * analysis throws on has no diagnostics and a line in the log, written once until the document has
 * been read again: the throw must not reach the loop that reads the others.
 */
export class Lint {
  private readonly failing = new Set<string>();

  constructor(private readonly log: (line: string) => void) {}

  issues(key: string, text: string, kotlin: boolean, prefix: string, constants: string[], defined: string[] | null, others: DriftFacts[] | null): Issue[] {
    try {
      const found = diagnose(text, kotlin, prefix, constants, defined, others);
      this.failing.delete(key);
      return found;
    } catch (e) {
      if (!this.failing.has(key)) this.log(`No diagnostics for ${key}: the analysis failed on it (${e instanceof Error ? `${e.name}: ${e.message}` : String(e)}).`);
      this.failing.add(key);
      return [];
    }
  }
}
