import assert from "node:assert/strict";

/*
 * The TypeScript twin of Growth.kt in streamlord-core/src/testCommon: the work on ten inputs of a
 * size is timed against the work on one input of ten times that size, so both take about as long
 * and a busy machine slows both alike. Read once, the second takes about ten times as long as one
 * of the first; read again for each part, a hundred. Thirty-five lies between with room for noise.
 */
const FACTOR = 10;
const LIMIT = 35;

/**
 * Fails when `work` on an `input` of ten times `size` takes 35 times as long as on one of `size`,
 * or longer. Each input is built untimed and used once. The two are timed in turn five times, after
 * a warm-up, and the fastest of each compared; a size whose work is too quick to time is doubled.
 */
export function assertGrowsLinearly<T>(what: string, size: number, input: (n: number) => T, work: (input: T) => void): void {
  const time = (n: number, count: number) => {
    const inputs = Array.from({ length: count }, () => input(n));
    const started = performance.now();
    for (const it of inputs) work(it);
    return performance.now() - started;
  };
  let n = size;
  for (let warm = 0; warm < 2; warm++) {
    time(n, FACTOR);
    time(n * FACTOR, 1);
  }
  while (time(n, FACTOR) < 20 && n < size * 16) n *= 2;
  let small = Infinity;
  let large = Infinity;
  for (let run = 0; run < 5; run++) {
    small = Math.min(small, time(n, FACTOR));
    large = Math.min(large, time(n * FACTOR, 1));
  }
  const ratio = (FACTOR * large) / small;
  const ms = (t: number) => t.toFixed(1);
  if (ratio >= LIMIT) {
    assert.fail(
      `${what} grows faster than its size: ${ms(small / FACTOR)} ms at ${n}, ${ms(large)} ms at ${n * FACTOR}, ` +
        `${ratio.toFixed(1)} times as long for ${FACTOR} times the size; the limit is ${LIMIT}`,
    );
  }
}
