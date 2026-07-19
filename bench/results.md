# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). Scores
are JMH averages ± 99.9% confidence intervals, one forked JVM, 5 warmup + 5 measurement
iterations.

- Date: 2026-07-19, flagged commit `7f3e870`
- Hardware: Apple M3 Max, 64 GB, macOS 26.5.1
- JVM: Temurin OpenJDK 25.0.2, Scala 3.8.3, JMH 1.37
- Library versions: mainargs 0.7.8, case-app 2.1.0

## Compile time

Milliseconds to compile one generated source file with a warm in-process `dotty` driver.
`baseline` is the same declarations with no parsing library — the compiler's floor for the file
shape. Only comparisons within a row are meaningful.

| Scenario | baseline | flagged | mainargs | case-app |
|---|---|---|---|---|
| `options10` (10 mixed fields) | 51.5 ± 2.9 | 177.6 ± 13.2 | 252.7 ± 21.3 | 707.0 ± 130.1 |
| `options25` (25 defaulted fields) | 48.5 ± 2.0 | 296.0 ± 15.0 | 256.4 ± 16.7 | 651.8 ± 230.8 |
| `commands` (3 subcommands) | 50.3 ± 8.2 | 174.5 ± 5.4 | 325.8 ± 50.8 | 522.2 ± 93.5 |

Derivation cost over the baseline: flagged adds ~125–250 ms, mainargs ~200–275 ms, case-app
~470–655 ms. flagged is the cheapest of the three on `options10` and `commands`; on the wide
`options25` class mainargs is somewhat cheaper — flagged's per-field inline expansion volume
grows faster than mainargs' macro there, even though the inline *depth* is logarithmic.

## Parse latency and allocation

Time and bytes allocated per successful parse (`gc.alloc.rate.norm`), parser instances built
once in setup.

### Scenarios all three libraries support

| Scenario | flagged | mainargs | case-app |
|---|---|---|---|
| `empty` — µs/op | 0.078 ± 0.002 | 0.141 ± 0.002 | 0.078 ± 0.002 |
| `empty` — B/op | 656 | 1 064 | 536 |
| `simple` — µs/op | 0.252 ± 0.003 | 0.894 ± 0.018 | 1.227 ± 0.018 |
| `simple` — B/op | 1 472 | 5 368 | 8 144 |
| `repeated` — µs/op | 0.322 ± 0.010 | 1.013 ± 0.151 | 4.180 ± 0.047 |
| `repeated` — B/op | 1 736 | 5 176 | 24 584 |

### flagged × mainargs (short clusters, typed leftover)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.232 ± 0.003 | 1.157 ± 0.028 |
| `bundled` — B/op | 1 376 | 6 600 |
| `leftover` — µs/op | 0.215 ± 0.005 | 0.270 ± 0.006 |
| `leftover` — B/op | 1 440 | 2 784 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.188 ± 0.001 | 2.033 ± 0.028 |
| `counter` — B/op | 1 008 | 12 224 |
| `group` — µs/op | 0.317 ± 0.008 | 3.026 ± 0.038 |
| `group` — B/op | 1 720 | 17 544 |

On non-trivial argument lists flagged parses in 0.19–0.33 µs across all scenarios, 3–5× faster
than mainargs and 5–13× faster than case-app on the same inputs, allocating 2–14× less. The
`empty` scenario (defaults only) is effectively a tie with case-app. The closest contest is
mainargs' `Leftover`, whose token pass-through is already minimal.

CLI parsing happens once per process, so parse latency is rarely a deciding factor; the compile
table is the practically relevant one, and the allocation column mostly matters as a proxy for
work done per token.
