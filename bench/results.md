# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). Scores
are JMH averages ± 99.9% confidence intervals, one forked JVM, 5 warmup + 5 measurement
iterations. Compile-time tables were measured at `7062642` (the engine work since does not
touch derivation); JMH runtime tables are a single run at `0e8ccc4`; the cross-platform
table's flagged rows are at `3869d4f`, its mainargs/case-app rows at `23029bc` — their code
paths did not change in between.

- Date: 2026-07-20, flagged commit `0e8ccc4`
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
~420–465 ms; flagged is the cheapest of the three in every scenario.

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
once in setup. The engine's hot path allocates nothing per token: parsers write successes into
value slots (`Result.done` signalling, no `Ok` boxing), per-slot state is primitive arrays,
lookups return null instead of `Option`, a long token is its own display spelling, and no
string is created unless it is part of an error being reported. Function values appear only
where user code supplies them (`Parser.of`, `emap`, custom combinators).

### Scenarios all three libraries support

| Scenario | flagged | mainargs | case-app |
|---|---|---|---|
| `empty` — µs/op | 0.038 ± 0.001 | 0.137 ± 0.005 | 0.076 ± 0.003 |
| `empty` — B/op | 256 | 1 064 | 536 |
| `simple` — µs/op | 0.098 ± 0.002 | 0.959 ± 0.012 | 1.230 ± 0.068 |
| `simple` — B/op | 512 | 5 392 | 8 144 |
| `repeated` — µs/op | 0.126 ± 0.004 | 0.904 ± 0.040 | 3.782 ± 0.071 |
| `repeated` — B/op | 704 | 5 488 | 23 528 |

### flagged × mainargs (short clusters, typed leftover, `Map[K,V]`)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.101 ± 0.003 | 1.182 ± 0.035 |
| `bundled` — B/op | 552 | 6 776 |
| `leftover` — µs/op | 0.148 ± 0.004 | 0.267 ± 0.005 |
| `leftover` — B/op | 752 | 2 760 |
| `map` — µs/op | 0.256 ± 0.006 | 0.470 ± 0.016 |
| `map` — B/op | 952 | 3 512 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.081 ± 0.005 | 1.808 ± 0.064 |
| `counter` — B/op | 480 | 11 984 |
| `group` — µs/op | 0.142 ± 0.004 | 2.775 ± 0.084 |
| `group` — B/op | 672 | 17 496 |

On non-trivial argument lists flagged parses in 0.08–0.26 µs across all scenarios, 1.8–30×
faster than mainargs and case-app on the same inputs, allocating 4–33× less — the remaining
bytes are the parse's actual output (the config object, `Some` wrappers for declared Option
fields, value substrings of `=`-forms and `k=v` entries) plus one set of per-parse state
arrays. The closest contests are mainargs' `Leftover` and `Map` (1.8×), whose per-token work
is already minimal.

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
| empty — flagged | 39 | 278 | 162 | 162 | 110 |
| empty — mainargs | 226 | 1 317 | 1 017 | 624 | 448 |
| empty — case-app | 138 | 354 | 265 | 256 | 164 |
| simple — flagged | 130 | 850 | 768 | 390 | 280 |
| simple — mainargs | 1 422 | 6 577 | 6 585 | 3 380 | 2 369 |
| simple — case-app | 1 643 | 4 927 | 3 313 | 5 911 | 3 252 |
| repeated — flagged | 192 | 1 278 | 995 | 511 | 382 |
| repeated — mainargs | 1 185 | 6 941 | 6 622 | 3 520 | 2 372 |
| repeated — case-app | 4 396 | 12 948 | 9 439 | 18 052 | 9 315 |
| bundled — flagged | 92 | 809 | 516 | 332 | 241 |
| bundled — mainargs | 1 305 | 7 670 | 6 415 | 3 819 | 3 014 |
| counter — flagged | 82 | 778 | 590 | 347 | 234 |
| counter — case-app | 2 015 | 6 992 | 4 125 | 8 325 | 4 833 |
| group — flagged | 149 | 1 268 | 994 | 551 | 403 |
| group — case-app | 3 390 | 9 899 | 6 273 | 12 428 | 7 085 |
| leftover — flagged | 177 | 2 066 | 843 | 575 | 438 |
| leftover — mainargs | 368 | 2 615 | 1 570 | 1 238 | 1 133 |

flagged is the fastest of the three on every scenario on every platform. In the maxed Native
build flagged parses in 0.23–0.44 µs — ~2–3× the JVM and ahead of Scala.js by 2–5×. The
WebAssembly backend beats the JavaScript one on every flagged scenario.

Why Native trails the JVM: an ahead-of-time build has no profile-guided optimization, so the
remaining polymorphic dispatch and the workload's own allocations stay real, where HotSpot
devirtualizes and escape-analyzes them after profiling. The engine keeps that residue small —
no closures on the hot path, successes written straight into value slots, an Option-free
routing loop, and one virtual method per value parser (a small dispatch surface is what an
AOT optimizer rewards most) — and the recommended build configuration does the rest: thin
LTO inlines across module boundaries into the Scala Native runtime, and for a
parse-once-and-exit CLI binary, no-GC-plus-process-teardown is a sound memory strategy, not
a benchmark trick.

CLI parsing happens once per process, so parse latency is rarely a deciding factor; the compile
table is the practically relevant one, and the allocation column mostly matters as a proxy for
work done per token.
