# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). Scores
are JMH averages ± 99.9% confidence intervals, one forked JVM, 5 warmup + 5 measurement
iterations; all tables below are from a single run.

- Date: 2026-07-20, flagged commit `ae9fdb0`
- Hardware: Apple M3 Max, 64 GB, macOS 26.5.1
- JVM: Temurin OpenJDK 25.0.2, Scala 3.8.3, JMH 1.37
- Library versions: mainargs 0.7.8, case-app 2.1.0

## Compile time

Milliseconds to compile one generated source file with a warm in-process `dotty` driver.
`baseline` is the same declarations with no parsing library — the compiler's floor for the file
shape. Only comparisons within a row are meaningful.

| Scenario | baseline | flagged | mainargs | case-app |
|---|---|---|---|---|
| `options10` (10 mixed fields) | 45.7 ± 1.7 | 108.1 ± 13.9 | 245.6 ± 18.8 | 442.8 ± 51.6 |
| `options25` (25 defaulted fields) | 41.5 ± 1.7 | 122.7 ± 10.4 | 253.5 ± 20.6 | 495.1 ± 18.1 |
| `commands` (3 subcommands) | 42.9 ± 3.9 | 108.1 ± 8.2 | 252.8 ± 21.2 | 446.7 ± 27.6 |

Derivation cost over the baseline: flagged adds ~60–80 ms, mainargs ~200–210 ms, case-app
~400–455 ms; flagged is the cheapest of the three in every scenario. (Before the optimization
rounds — per-field expansion collapse, bottom-up mergeable walk summaries, match-type field
computation — flagged was at 178/296/175 ms and lost `options25` to mainargs.)

### Scaling with field count

`bench.ScalingProbe` (same warm driver, best of five, one options class of N `Int` fields;
`flagged@` has every second field `@name`-annotated):

| n fields | flagged | flagged@ | mainargs |
|---|---|---|---|
| 4 | 132 | 134 | 221 |
| 8 | 129 | 131 | 258 |
| 16 | 127 | 139 | 235 |
| 32 | 152 | 180 | 258 |
| 64 | 192 | 265 | 266 |
| 128 | 328 | 553 | 291 |

Marginal cost is roughly 2 ms per unannotated field (~3.5 ms with half the fields annotated)
and approximately constant across the range — compile time grows linearly with field count,
with a no-derivation floor around 118 ms for the 128-field file. flagged is faster than
mainargs up to and including 64 fields and within ~13% at 128. A 64-field annotated class
compiles at the default `-Xmax-inlines`. A JFR profile of the looped driver
(`bench.ProfileProbe`) shows no single hotspot — implicit search and match-type reduction are
negligible — and `bench.AblationProbe` breaks derivation cost down by component.

## Parse latency and allocation

Time and bytes allocated per successful parse (`gc.alloc.rate.norm`), parser instances built
once in setup.

### Scenarios all three libraries support

| Scenario | flagged | mainargs | case-app |
|---|---|---|---|
| `empty` — µs/op | 0.077 ± 0.002 | 0.136 ± 0.003 | 0.076 ± 0.002 |
| `empty` — B/op | 672 | 1 064 | 536 |
| `simple` — µs/op | 0.242 ± 0.005 | 0.856 ± 0.054 | 1.243 ± 0.056 |
| `simple` — B/op | 1 472 | 5 368 | 8 504 |
| `repeated` — µs/op | 0.304 ± 0.013 | 0.810 ± 0.015 | 4.393 ± 0.095 |
| `repeated` — B/op | 1 736 | 5 176 | 25 032 |

### flagged × mainargs (short clusters, typed leftover)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.237 ± 0.007 | 1.122 ± 0.047 |
| `bundled` — B/op | 1 432 | 6 696 |
| `leftover` — µs/op | 0.202 ± 0.002 | 0.257 ± 0.005 |
| `leftover` — B/op | 1 440 | 2 760 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.181 ± 0.003 | 1.969 ± 0.034 |
| `counter` — B/op | 1 008 | 12 224 |
| `group` — µs/op | 0.304 ± 0.007 | 2.885 ± 0.099 |
| `group` — B/op | 1 720 | 17 272 |

On non-trivial argument lists flagged parses in 0.18–0.31 µs across all scenarios, 2.7–14×
faster than mainargs and case-app on the same inputs, allocating 2–14× less. The `empty`
scenario (defaults only) is effectively a tie with case-app. The closest contest is mainargs'
`Leftover` (1.3× on time), whose token pass-through is already minimal.

### Cross-platform (Scala.js, Wasm, Scala Native)

`bench-portable/` re-runs the same scenarios against the same parser definitions on the
platforms JMH cannot cover, with a calibrated best-of-5-rounds timer (~250 ms rounds). The JVM
column below uses the same portable harness for comparability — its closure indirection adds a
small constant to every library, so the JMH tables above stay canonical for JVM. Scala.js
1.21.0 on Node 26 (`--js`, plus `--js-emit-wasm --js-module-kind es` for the WebAssembly
backend), Scala Native 0.5 in `release-fast`.

ns per parse, best of 5 rounds:

| Benchmark | JVM | JS | JS/Wasm | Native |
|---|---|---|---|---|
| empty — flagged | 154 | 430 | 369 | 624 |
| empty — mainargs | 231 | 1 573 | 1 022 | 631 |
| empty — case-app | 134 | 352 | 268 | 244 |
| simple — flagged | 268 | 893 | 996 | 1 242 |
| simple — mainargs | 1 084 | 6 700 | 6 420 | 3 351 |
| simple — case-app | 1 455 | 4 593 | 3 261 | 5 847 |
| repeated — flagged | 302 | 1 118 | 1 180 | 1 676 |
| repeated — mainargs | 1 024 | 6 824 | 6 313 | 3 393 |
| repeated — case-app | 4 768 | 11 953 | 9 115 | 17 507 |
| bundled — flagged | 221 | 841 | 792 | 1 106 |
| bundled — mainargs | 1 259 | 7 838 | 6 204 | 3 711 |
| counter — flagged | 205 | 683 | 845 | 897 |
| counter — case-app | 2 041 | 6 323 | 4 065 | 8 066 |
| group — flagged | 323 | 1 204 | 1 189 | 1 648 |
| group — case-app | 3 012 | 9 479 | 6 216 | 11 965 |
| leftover — flagged | 255 | 1 395 | 958 | 1 278 |
| leftover — mainargs | 363 | 2 691 | 1 568 | 1 202 |

flagged is the fastest of the three on every non-trivial scenario on every platform; the
exceptions are the `empty` scenario, where case-app's near-no-op wins everywhere, and Native's
`leftover`, where mainargs ties. Cross-platform slowdowns for flagged are roughly 3–5× on
Scala.js and 4–7× on Native versus the JVM; the WebAssembly backend is broadly comparable to
the JavaScript one on these workloads (somewhat faster for case-app, a wash for flagged).

CLI parsing happens once per process, so parse latency is rarely a deciding factor; the compile
table is the practically relevant one, and the allocation column mostly matters as a proxy for
work done per token.
