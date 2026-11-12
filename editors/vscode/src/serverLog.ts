import { isServerProblem, plainLogLine, serverStartedAt } from "./analysis.ts";

/** A server the editor saw start, by the line it logged: where it listens, and what the editor calls it. */
export interface RunningServer {
  /** The base URL, as `{{baseUrl}}` would hold it: no trailing slash, the context path included. */
  url: string;
  /** The debug session it was started from. */
  name: string;
}

const KEEP = 500;
const SHOWN = 10;

interface Run {
  name: string;
  url: string | null;
  startedAt: number;
  lines: { at: number; text: string }[];
  partial: string;
}

/**
 * The servers started from the editor, read from their own output. The port a server listens on
 * is set in a profile, an environment variable or a launch configuration, and the inspector
 * cannot read all of those; the server says it once when it starts, and that line is the answer
 * wherever the port came from. The last lines are kept too, so a failed request can show the
 * warning the server logged about it.
 */
export class RunningServers {
  private readonly runs = new Map<string, Run>();
  private readonly listeners: (() => void)[] = [];

  /** Take output of the process `id`, called `name`, as it arrives, which is not always a whole line. */
  feed(id: string, name: string, text: string, now = Date.now()): void {
    const run = this.runs.get(id) ?? { name, url: null, startedAt: 0, lines: [], partial: "" };
    this.runs.set(id, run);
    run.partial += text;
    let end: number;
    while ((end = run.partial.indexOf("\n")) >= 0) {
      const line = plainLogLine(run.partial.slice(0, end).replace(/\r$/, ""));
      run.partial = run.partial.slice(end + 1);
      if (run.url === null) {
        const url = serverStartedAt(line) ?? null;
        if (url !== null) {
          run.url = url;
          run.startedAt = now;
          this.changed();
        }
      }
      run.lines.push({ at: now, text: line });
      if (run.lines.length > KEEP) run.lines.shift();
    }
  }

  /** The process `id` has stopped. */
  end(id: string): void {
    const run = this.runs.get(id);
    this.runs.delete(id);
    if (run?.url) this.changed();
  }

  /** The server that started last and still runs, or null. */
  current(): RunningServer | null {
    let best: Run | null = null;
    for (const run of this.runs.values()) if (run.url !== null && (best === null || run.startedAt > best.startedAt)) best = run;
    return best && best.url !== null ? { url: best.url, name: best.name } : null;
  }

  /** The warnings and errors the servers logged at or after `since`, oldest first, at most ten. */
  problemsSince(since: number): string[] {
    return [...this.runs.values()]
      .flatMap((run) => run.lines.filter((l) => l.at >= since && isServerProblem(l.text)))
      .sort((a, b) => a.at - b.at)
      .slice(0, SHOWN)
      .map((l) => l.text);
  }

  /** Call `listener` when a server starts or stops, so what shows `{{baseUrl}}` can follow. */
  onChange(listener: () => void): { dispose(): void } {
    this.listeners.push(listener);
    return { dispose: () => this.listeners.splice(this.listeners.indexOf(listener), 1) };
  }

  private changed(): void {
    for (const listener of this.listeners) listener();
  }
}
