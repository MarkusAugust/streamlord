import assert from "node:assert/strict";

/*
 * The TypeScript twin of Growth.kt in streamlord-core/src/testCommon: the work is timed on 64
 * inputs of a size, on 8 of eight times it and on 1 of 64 times it, each about as long as the
 * others, so a busy machine slows all three alike. Over 64 times the size, work that grows with
 * it costs as much per unit at the end as at the start, and work that grows with its square 64
 * times as much; eight times lies midway.
 */
const STEP = 8;
const LIMIT = 8;
// A measurement this long means the work has run away: the test stops rather than wait for it.
const DEADLINE_MS = 20_000;

const sizeOf = (k: number) => STEP ** k;
const count = (k: number) => sizeOf(2 - k);
const ms = (t: number) => t.toFixed(1);

/**
 * Fails when `work` costs eight times as much per unit of size, or more, on an `input` of 64 times
 * `size` as on one of `size`. Each input is built untimed and used once. The three sizes are timed
 * in turn five times, after a warm-up, and the fastest of each kept. A size whose work is too quick
 * to time, or whose time is mostly a cost each call has whatever its size, is doubled first. It
 * fails as soon as one measurement takes 20 seconds, or when the growth from the smallest size to
 * the middle one is past the limit or says the largest would take that long.
 */
export function assertGrowsLinearly<T>(what: string, size: number, input: (n: number) => T, work: (input: T) => void): void {
  const report = (n: number, times: number[]) => times.map((t, k) => `${ms(t)} ms for ${count(k)} at ${n * sizeOf(k)}`).join(", ");
  const time = (n: number, k: number) => {
    const inputs = Array.from({ length: count(k) }, () => input(n * sizeOf(k)));
    const started = performance.now();
    for (const it of inputs) work(it);
    const took = performance.now() - started;
    if (took > DEADLINE_MS) assert.fail(`${what} ran away: ${ms(took)} ms for ${count(k)} at ${n * sizeOf(k)}`);
    return took;
  };
  let n = size;
  for (let warm = 0; warm < 2; warm++) for (let k = 0; k < 2; k++) time(n, k);
  for (;;) {
    const small = time(n, 0);
    // A cost each call has whatever its size, which 64 calls pay where 8 pay it an eighth as often,
    // hides how the work grows: the size is doubled until it is under a quarter of the smallest's time.
    const fixed = (small - time(n, 1)) / (count(0) - count(1));
    const tooQuick = small < 30 && n < size * 8;
    const fixedBound = fixed * count(0) >= small / 4 && n < size * 16;
    if (!tooQuick && !fixedBound) break;
    n *= 2;
  }
  const best = [Infinity, Infinity, Infinity];
  for (let run = 0; run < 5; run++) {
    for (let k = 0; k < 3; k++) {
      if (k === 2) {
        const step = best[1]! / best[0]!;
        if (step >= LIMIT || best[1]! * step > DEADLINE_MS) assert.fail(`${what} grows faster than its size: ${report(n, best.slice(0, 2))}`);
      }
      best[k] = Math.min(best[k]!, time(n, k));
    }
  }
  const growth = best[2]! / best[0]!;
  if (growth >= LIMIT) {
    const exponent = 1 + Math.log(growth) / Math.log(sizeOf(2));
    assert.fail(
      `${what} grows faster than its size: ${report(n, best)}: ${growth.toFixed(1)} times the cost per unit at 64 times the size, ` +
        `as the size to the power ${exponent.toFixed(2)}; the limit is ${LIMIT}`,
    );
  }
}
