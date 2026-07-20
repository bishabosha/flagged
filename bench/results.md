# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). Scores
are JMH averages ± 99.9% confidence intervals, one forked JVM, 5 warmup + 5 measurement
iterations; all tables below are from a single run.

- Date: 2026-07-20, flagged commit `7062642`
- Hardware: Apple M3 Max, 64 GB, macOS 26.5.1
- JVM: Temurin OpenJDK 25.0.2, Scala 3.8.3, JMH 1.37
- Library versions: mainargs 0.7.8, case-app 2.1.0

## Compile time

Milliseconds to compile one generated source file with a warm in-process `dotty` driver.
`baseline` is the same declarations with no parsing library — the compiler's floor for the file
shape. Only comparisons within a row are meaningful.

| Scenario | baseline | flagged | mainargs | case-app |
|---|---|---|---|---|
| `options10` (10 mixed fields) | 50.1 ± 2.4 | 113.3 ± 7.6 | 235.7 ± 25.5 | 472.9 ± 52.2 |
| `options25` (25 defaulted fields) | 45.7 ± 2.0 | 132.5 ± 6.7 | 238.9 ± 14.9 | 511.0 ± 25.7 |
| `commands` (3 subcommands) | 47.1 ± 4.8 | 119.3 ± 8.3 | 250.4 ± 19.5 | 473.8 ± 42.6 |

Derivation cost over the baseline: flagged adds ~63–87 ms, mainargs ~185–203 ms, case-app
~420–465 ms; flagged is the cheapest of the three in every scenario. (Before the optimization
rounds — per-field expansion collapse, bottom-up mergeable walk summaries, match-type field
computation — flagged was at 178/296/175 ms and lost `options25` to mainargs.)

### Scaling with field count

`bench.ScalingProbe` (same warm driver, best of five, one options class of N `Int` fields;
`flagged@` has every second field `@name`-annotated):

| n fields | flagged | flagged@ | mainargs |
|---|---|---|---|
| 4 | 145 | 146 | 254 |
| 8 | 140 | 142 | 254 |
| 16 | 142 | 153 | 245 |
| 32 | 164 | 197 | 258 |
| 64 | 212 | 292 | 271 |
| 128 | 353 | 592 | 307 |

Marginal cost is roughly 2 ms per unannotated field (~3.5 ms with half the fields annotated)
and approximately constant across the range — compile time grows linearly with field count,
with a no-derivation floor around 135 ms for the 128-field file. flagged is ahead of mainargs
up to and including 64 fields and within ~15% at 128. A 64-field annotated class compiles at
the default `-Xmax-inlines`. A JFR profile of the looped driver (`bench.ProfileProbe`) shows no
single hotspot — implicit search and match-type reduction are negligible — and
`bench.AblationProbe` breaks derivation cost down by component.

## Parse latency and allocation

Time and bytes allocated per successful parse (`gc.alloc.rate.norm`), parser instances built
once in setup. The engine's hot path allocates no per-token objects: parsers write successes
into value slots (`Result.done` signalling, no `Ok` boxing), per-slot state is primitive
arrays, and function values appear only where user code supplies them (`Parser.of`, `emap`,
custom combinators).

### Scenarios all three libraries support

| Scenario | flagged | mainargs | case-app |
|---|---|---|---|
| `empty` — µs/op | 0.070 ± 0.002 | 0.139 ± 0.003 | 0.076 ± 0.001 |
| `empty` — B/op | 544 | 1 064 | 536 |
| `simple` — µs/op | 0.203 ± 0.004 | 0.982 ± 0.014 | 1.223 ± 0.038 |
| `simple` — B/op | 1 152 | 5 392 | 8 144 |
| `repeated` — µs/op | 0.300 ± 0.029 | 0.960 ± 0.017 | 3.859 ± 0.022 |
| `repeated` — B/op | 1 776 | 5 200 | 24 200 |

### flagged × mainargs (short clusters, typed leftover)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.184 ± 0.033 | 1.186 ± 0.013 |
| `bundled` — B/op | 1 160 | 6 776 |
| `leftover` — µs/op | 0.176 ± 0.002 | 0.266 ± 0.002 |
| `leftover` — B/op | 992 | 2 760 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.137 ± 0.006 | 2.032 ± 0.029 |
| `counter` — B/op | 872 | 12 224 |
| `group` — µs/op | 0.264 ± 0.009 | 2.976 ± 0.086 |
| `group` — B/op | 1 440 | 17 544 |

On non-trivial argument lists flagged parses in 0.14–0.30 µs across all scenarios, 3–15×
faster than mainargs and case-app on the same inputs, allocating 3–17× less — the remaining
bytes are the parse's actual output (the config object, `Some` wrappers, string slices) plus
one set of per-parse state arrays. The closest contest is mainargs' `Leftover` (1.5×), whose
token pass-through is already minimal.

### Cross-platform (Scala.js, Wasm, Scala Native)

`bench-portable/` re-runs the same scenarios against the same parser definitions on the
platforms JMH cannot cover, with a calibrated best-of-5-rounds timer (~250 ms rounds). The JVM
column below uses the same portable harness for comparability — its closure indirection adds a
small constant to every library, so the JMH tables above stay canonical for JVM. Scala.js
1.21.0 on Node 26 (`--js`, plus `--js-emit-wasm --js-module-kind es` for the WebAssembly
backend), Scala Native 0.5 in `release-fast`; the last column is the recommended Native
release configuration (`release-full --native-lto thin --native-gc none`).

ns per parse, best of 5 rounds:

| Benchmark | JVM | JS | JS/Wasm | Native | Native (max) |
|---|---|---|---|---|---|
| empty — flagged | 150 | 313 | 318 | 548 | 319 |
| empty — mainargs | 274 | 1 333 | 1 033 | 651 | 444 |
| empty — case-app | 134 | 366 | 274 | 254 | 167 |
| simple — flagged | 261 | 707 | 946 | 1 080 | 668 |
| simple — mainargs | 1 299 | 6 533 | 6 530 | 3 469 | 2 366 |
| simple — case-app | 1 626 | 4 769 | 3 407 | 6 023 | 3 237 |
| repeated — flagged | 369 | 1 168 | 1 396 | 1 516 | 1 010 |
| repeated — mainargs | 1 095 | 6 688 | 6 524 | 3 539 | 2 481 |
| repeated — case-app | 5 550 | 12 388 | 9 729 | 18 191 | 9 664 |
| bundled — flagged | 191 | 650 | 717 | 928 | 604 |
| bundled — mainargs | 1 254 | 7 546 | 6 347 | 3 835 | 2 848 |
| counter — flagged | 173 | 553 | 724 | 745 | 499 |
| counter — case-app | 2 322 | 6 545 | 4 256 | 8 390 | 4 640 |
| group — flagged | 283 | 998 | 1 152 | 1 438 | 950 |
| group — case-app | 3 615 | 9 779 | 6 396 | 12 471 | 7 011 |
| leftover — flagged | 285 | 1 299 | 833 | 887 | 639 |
| leftover — mainargs | 344 | 2 515 | 1 601 | 1 266 | 1 065 |

flagged is the fastest of the three on every non-trivial scenario on every platform (the
`empty` scenario remains case-app's near-no-op win). Cross-platform slowdowns for flagged are
roughly 2.5–4.5× on Scala.js and, in the maxed Native build, ~2.5–3.5× versus the JVM — with
the maxed build ahead of Scala.js. The WebAssembly backend is broadly comparable to the
JavaScript one on these workloads.

Why Native trails the JVM: an ahead-of-time build has no profile-guided optimization, so the
remaining polymorphic dispatch and the workload's own allocations stay real, where HotSpot
devirtualizes and escape-analyzes them after profiling. The gap has been engineered down in
two steps — removing deliberate closures from the hot path, then the slot-write protocol that
eliminated per-token allocations (`Occ` records, occurrence buffers, `Ok` boxing) — and the
recommended build configuration does the rest: thin LTO inlines across module boundaries into
the Scala Native runtime, and for a parse-once-and-exit CLI binary, no-GC-plus-process-teardown
is a sound memory strategy, not a benchmark trick.

CLI parsing happens once per process, so parse latency is rarely a deciding factor; the compile
table is the practically relevant one, and the allocation column mostly matters as a proxy for
work done per token.
