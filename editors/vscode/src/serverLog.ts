/**
 * What a server says about itself in its log: where it listens, and which of its lines belong
 * under a request that failed. Editor-independent, and the same rules as the analysis module's
 * `ServerLog.kt`, which the IntelliJ plugin uses.
 */

/** A server the editor saw start, by the line it logged: where it listens, and what the editor calls it. */
export interface RunningServer {
  /** The base URL, as `{{baseUrl}}` would hold it: no trailing slash, the context path included. */
  url: string;
  /** The debug session it was started from. */
  name: string;
}

/*
 * Spring Boot writes "<server> started on " + its ports + " with context path '<path>'", the same
 * in 3.5 and 4.0 for Tomcat, Jetty and Undertow, and without a context path for Netty. One port
 * reads "port 9102 (http)", Jetty's "(http/1.1)"; Boot 2 wrote "port(s): 9102 (http)".
 */
const SPRING = /\b(?:Tomcat|Jetty|Undertow|Netty) started on port(?:s|\(s\))?:? (\d+)(?: \((\w+)[^)]*\))?(?:.*? with context path '([^']*)')?/;

// Ktor writes "Responding at " + the connector, such as http://0.0.0.0:8080.
const KTOR = /\bResponding at (https?):\/\/(\[[^\]]+]|[^\s/:]+):(\d+)/;

const ANY_HOST = new Set(["0.0.0.0", "[::]", "::", "*"]);
const PROBLEM = /\b(?:WARN|WARNING|ERROR|SEVERE|FATAL)\b/;

// Spring Boot colours the level when the console takes it: ESC[31mERROR has no word boundary.
const ANSI = /\u001B\[[0-9;]*[A-Za-z]/g;

/** `line` without the escape sequences a coloured console log holds. */
export function plain(line: string): string {
  return line.replace(ANSI, "");
}

/** The base URL a server announces in `line`, or null when the line announces nothing. */
export function startedAt(line: string): string | null {
  const text = plain(line);
  const spring = SPRING.exec(text);
  if (spring) return `${spring[2] === "https" ? "https" : "http"}://localhost:${spring[1]}${(spring[3] ?? "").replace(/\/+$/, "")}`;
  const ktor = KTOR.exec(text);
  if (ktor) return `${ktor[1]}://${ANY_HOST.has(ktor[2] ?? "") ? "localhost" : ktor[2]}:${ktor[3]}`;
  return null;
}

/** Whether `line` belongs under a failed request: a warning or an error, as the common loggers write the level. */
export function isProblem(line: string): boolean {
  return PROBLEM.test(plain(line));
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
      const line = plain(run.partial.slice(0, end).replace(/\r$/, ""));
      run.partial = run.partial.slice(end + 1);
      if (run.url === null) {
        const url = startedAt(line);
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
      .flatMap((run) => run.lines.filter((l) => l.at >= since && isProblem(l.text)))
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
