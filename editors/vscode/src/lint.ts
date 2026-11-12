import { diagnose, type Issue } from "./analysis.ts";

/** What the analysis finds in the documents the editor has open, each known by [key]. */
export class Lint {
  constructor(private readonly log: (line: string) => void) {}

  issues(key: string, text: string, kotlin: boolean, prefix: string, constants: string[], defined: string[] | null): Issue[] {
    return diagnose(text, kotlin, prefix, constants, defined);
  }
}
