/**
 * Dynamic time warping between two sequences of equal-dimension frames.
 * Sakoe-Chiba band, Euclidean frame cost, normalised by n + m so short and long phrases compare fairly.
 */

function frameCost(a: Float32Array, b: Float32Array): number {
  let s = 0
  for (let i = 0; i < a.length; i++) {
    const d = a[i] - b[i]
    s += d * d
  }
  return Math.sqrt(s)
}

export function dtw(a: Float32Array[], b: Float32Array[], bandFraction = 0.25): number {
  const n = a.length
  const m = b.length
  if (n === 0 || m === 0) return Infinity
  const band = Math.max(Math.abs(n - m), Math.ceil(bandFraction * Math.max(n, m)))

  let prev = new Float64Array(m + 1).fill(Infinity)
  let cur = new Float64Array(m + 1).fill(Infinity)
  prev[0] = 0

  for (let i = 1; i <= n; i++) {
    cur.fill(Infinity)
    const lo = Math.max(1, i - band)
    const hi = Math.min(m, i + band)
    for (let j = lo; j <= hi; j++) {
      const best = Math.min(prev[j], cur[j - 1], prev[j - 1])
      cur[j] = frameCost(a[i - 1], b[j - 1]) + best
    }
    ;[prev, cur] = [cur, prev]
  }
  return prev[m] / (n + m)
}
