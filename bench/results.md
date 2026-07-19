# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). Scores
are JMH averages ± 99.9% confidence intervals, one forked JVM, 5 warmup + 5 measurement
iterations.

- Date: 2026-07-19, flagged commit `fd51a07`
- Hardware: Apple M3 Max, 64 GB, macOS 26.5.1
- JVM: Temurin OpenJDK 25.0.2, Scala 3.8.3, JMH 1.37
- Library versions: mainargs 0.7.8, case-app 2.1.0

## Compile time

Milliseconds to compile one generated source file with a warm in-process `dotty` driver.
`baseline` is the same declarations with no parsing library — the compiler's floor for the file
shape. Only comparisons within a row are meaningful.

| Scenario | baseline | flagged | mainargs | case-app |
|---|---|---|---|---|
| `options10` (10 mixed fields) | 53.7 ± 11.2 | 115.6 ± 8.9 | 244.7 ± 32.2 | 486.7 ± 46.3 |
| `options25` (25 defaulted fields) | 47.7 ± 3.2 | 161.2 ± 13.3 | 247.3 ± 19.7 | 528.4 ± 32.8 |
| `commands` (3 subcommands) | 49.1 ± 5.4 | 114.0 ± 6.4 | 258.7 ± 20.6 | 524.5 ± 56.5 |

Derivation cost over the baseline: flagged adds ~60–115 ms, mainargs ~190–210 ms, case-app
~430–480 ms; flagged is the cheapest of the three in every scenario. (Before the per-field
expansion collapse in `fd51a07`, flagged was at 178/296/175 ms and lost `options25` to
mainargs.)

### Scaling with field count

`bench.ScalingProbe` (same warm driver, best of five, one options class of N `Int` fields;
`flagged@` has every second field `@name`-annotated):

| n fields | flagged | flagged@ | mainargs |
|---|---|---|---|
| 4 | 133 | 137 | 247 |
| 8 | 136 | 144 | 240 |
| 16 | 152 | 175 | 253 |
| 32 | 196 | 243 | 260 |
| 64 | 316 | 423 | 276 |
| 128 | 602 | 857 | 308 |

Marginal cost is roughly 3–5 ms per field and approximately constant across the range —
compile time grows near-linearly with field count. A 64-field annotated class compiles at the
default `-Xmax-inlines`. A JFR profile of the looped driver (`bench.ProfileProbe`) shows no
single hotspot: implicit search and match-type reduction are negligible, and the remaining
per-field cost is downstream phases traversing the code derivation expands.

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
