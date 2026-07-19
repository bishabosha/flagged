# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). Scores
are JMH averages ± 99.9% confidence intervals, one forked JVM, 5 warmup + 5 measurement
iterations; all tables below are from a single run.

- Date: 2026-07-20, flagged commit `b9f6856`
- Hardware: Apple M3 Max, 64 GB, macOS 26.5.1
- JVM: Temurin OpenJDK 25.0.2, Scala 3.8.3, JMH 1.37
- Library versions: mainargs 0.7.8, case-app 2.1.0

## Compile time

Milliseconds to compile one generated source file with a warm in-process `dotty` driver.
`baseline` is the same declarations with no parsing library — the compiler's floor for the file
shape. Only comparisons within a row are meaningful.

| Scenario | baseline | flagged | mainargs | case-app |
|---|---|---|---|---|
| `options10` (10 mixed fields) | 45.6 ± 1.9 | 103.2 ± 5.6 | 251.1 ± 32.1 | 463.4 ± 40.1 |
| `options25` (25 defaulted fields) | 41.6 ± 1.5 | 122.4 ± 7.4 | 260.6 ± 14.4 | 508.5 ± 44.5 |
| `commands` (3 subcommands) | 42.4 ± 3.8 | 108.9 ± 6.5 | 274.2 ± 8.0 | 449.1 ± 29.2 |

Derivation cost over the baseline: flagged adds ~58–81 ms, mainargs ~205–232 ms, case-app
~405–467 ms; flagged is the cheapest of the three in every scenario. (Before the optimization
rounds — per-field expansion collapse, bottom-up mergeable walk summaries, match-type field
computation — flagged was at 178/296/175 ms and lost `options25` to mainargs.)

### Scaling with field count

`bench.ScalingProbe` (same warm driver, best of five, one options class of N `Int` fields;
`flagged@` has every second field `@name`-annotated):

| n fields | flagged | flagged@ | mainargs |
|---|---|---|---|
| 4 | 127 | 133 | 240 |
| 8 | 126 | 132 | 218 |
| 16 | 125 | 138 | 222 |
| 32 | 154 | 182 | 240 |
| 64 | 193 | 268 | 255 |
| 128 | 331 | 544 | 345 |

Marginal cost is roughly 2 ms per unannotated field (~3.5 ms with half the fields annotated)
and approximately constant across the range — compile time grows linearly with field count,
with a no-derivation floor around 120 ms for the 128-field file. flagged is ahead of mainargs
across the whole range in this run. A 64-field annotated class compiles at the default
`-Xmax-inlines`. A JFR profile of the looped driver (`bench.ProfileProbe`) shows no single
hotspot — implicit search and match-type reduction are negligible — and `bench.AblationProbe`
breaks derivation cost down by component.

## Parse latency and allocation

Time and bytes allocated per successful parse (`gc.alloc.rate.norm`), parser instances built
once in setup. The parse path dispatches through parser methods; function values appear only
where user code supplies them (`Parser.of`, `emap`, custom combinators).

### Scenarios all three libraries support

| Scenario | flagged | mainargs | case-app |
|---|---|---|---|
| `empty` — µs/op | 0.077 ± 0.002 | 0.137 ± 0.007 | 0.078 ± 0.002 |
| `empty` — B/op | 656 | 1 064 | 536 |
| `simple` — µs/op | 0.237 ± 0.004 | 0.955 ± 0.020 | 1.215 ± 0.048 |
| `simple` — B/op | 1 424 | 5 392 | 8 360 |
| `repeated` — µs/op | 0.293 ± 0.003 | 0.950 ± 0.032 | 3.723 ± 0.162 |
| `repeated` — B/op | 1 720 | 5 224 | 23 528 |

### flagged × mainargs (short clusters, typed leftover)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.214 ± 0.010 | 1.021 ± 0.042 |
| `bundled` — B/op | 1 344 | 6 632 |
| `leftover` — µs/op | 0.199 ± 0.009 | 0.250 ± 0.010 |
| `leftover` — B/op | 1 400 | 2 680 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.169 ± 0.009 | 1.811 ± 0.065 |
| `counter` — B/op | 992 | 11 984 |
| `group` — µs/op | 0.297 ± 0.006 | 2.959 ± 0.065 |
| `group` — B/op | 1 656 | 17 272 |

On non-trivial argument lists flagged parses in 0.17–0.30 µs across all scenarios, 3–13×
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
| empty — flagged | 146 | 430 | 359 | 614 |
| empty — mainargs | 211 | 1 562 | 1 012 | 629 |
| empty — case-app | 128 | 357 | 261 | 245 |
| simple — flagged | 258 | 835 | 1 040 | 1 156 |
| simple — mainargs | 1 038 | 6 528 | 6 288 | 3 358 |
| simple — case-app | 1 407 | 4 592 | 3 288 | 5 865 |
| repeated — flagged | 300 | 1 154 | 1 428 | 1 529 |
| repeated — mainargs | 992 | 6 650 | 6 247 | 3 405 |
| repeated — case-app | 4 576 | 11 943 | 9 408 | 17 461 |
| bundled — flagged | 212 | 772 | 759 | 1 004 |
| bundled — mainargs | 1 175 | 7 522 | 6 140 | 3 753 |
| counter — flagged | 198 | 645 | 849 | 818 |
| counter — case-app | 2 171 | 6 374 | 4 391 | 8 152 |
| group — flagged | 316 | 1 121 | 1 259 | 1 493 |
| group — case-app | 3 235 | 9 448 | 6 363 | 12 041 |
| leftover — flagged | 240 | 1 282 | 937 | 1 250 |
| leftover — mainargs | 343 | 2 628 | 1 535 | 1 217 |

flagged is the fastest of the three on every non-trivial scenario on every platform; the
exceptions are the `empty` scenario, where case-app's near-no-op wins everywhere, and Native's
`leftover`, where mainargs ties. Cross-platform slowdowns for flagged are roughly 3–5× on
Scala.js and 4–6× on Native versus the JVM; the WebAssembly backend is broadly comparable to
the JavaScript one on these workloads (somewhat faster for case-app, a wash for flagged).

Why Native trails both: ablation on `simple — flagged` shows `release-full` recovers ~17% and
disabling the GC outright (`--native-gc none`) ~14%, so neither static-optimization headroom
nor collection cost explains the gap. The remainder is the absence of runtime profile-guided
optimization: the parse path dispatches through parser subtypes and `Result` combinators,
which HotSpot and V8 speculatively devirtualize, inline, and escape-analyze after profiling
(eliminating most of the short-lived allocation), while an ahead-of-time build keeps every
polymorphic dispatch and allocation real. Removing the deliberate closures from the hot path
(commit `b9f6856`) recovered a further 5–10% on every platform; what remains is the workload's
own allocation. Consistent with that, closure-heavy case-app is 1.5× slower on Native than on
JS, while table-driven mainargs is 2× faster on Native than on JS.

CLI parsing happens once per process, so parse latency is rarely a deciding factor; the compile
table is the practically relevant one, and the allocation column mostly matters as a proxy for
work done per token.
