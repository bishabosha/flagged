# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). All
tables were measured in one session at the commit below — the state that includes the
multi-token features (`Parser.Product`, `@split`, `@greedy`), the `Parser.Shared` splice model,
and the reworked derivation rules layer (field rules as one single-pass match type with
data-only scrutinees; see `bench.RuleCostProbe` for the measurements behind that encoding) —
with flagged on the benchmark classpath as its packaged jar (`FlaggedFromJar` in `build.mill`),
like the mainargs/case-app jars from the coursier cache. JMH scores are averages ± 99.9%
confidence intervals; the compile table is one forked JVM (3 warmup + 5 measurement
iterations), and the runtime tables (`RuntimeBench`, `MethodBench`, `BaselineBench`) are five
forked JVMs per benchmark (5 warmup + 5 measurement iterations each, all libraries alike), with
allocation from the same `-prof gc` runs.

- Date: 2026-07-24, flagged commit `5853215`
- Hardware: Apple M3 Max, 64 GB, macOS 26.5.1
- JVM: Temurin OpenJDK 25.0.2, Scala 3.8.3, JMH 1.37
- Library versions: mainargs 0.7.8, case-app 2.1.0; `realistic` runtime rows also scopt 4.1.0,
  scallop 5.1.0, picocli 4.7.6

## Compile time

Milliseconds to compile one generated source file with a warm in-process `dotty` driver.
`baseline` is the same declarations with no parsing library — the compiler's floor for the file
shape. Only comparisons within a row are meaningful.

| Scenario | baseline | flagged | mainargs | case-app |
|---|---|---|---|---|
| `options10` (10 mixed fields) | 50.5 ± 3.8 | 95.1 ± 8.5 | 223.8 ± 19.8 | 438.8 ± 77.1 |
| `options25` (25 defaulted fields) | 46.7 ± 4.3 | 116.1 ± 7.9 | 236.2 ± 9.7 | 476.2 ± 38.8 |
| `commands` (3 subcommands) | 48.5 ± 6.6 | 102.2 ± 8.2 | 240.9 ± 35.9 | 437.4 ± 51.5 |
| `methods` (3 command methods) | 28.9 ± 1.7 | 109.5 ± 6.3 | 236.3 ± 33.3 | 450.4 ± 58.2 |
| `realistic` (docker-style CLI) | 57.2 ± 4.8 | 177.2 ± 12.8 | 199.7 ± 21.3 | 499.0 ± 33.0 |

Derivation cost over the baseline: flagged adds ~45–120 ms, mainargs ~143–207 ms, case-app
~388–442 ms; flagged is the cheapest of the three in every scenario, including the wide
`realistic` interface. Relative to the previous published state, flagged's rows dropped by
25–35 ms per file from the rules-layer rework: the per-field validation is now a single pass
over the annotation slot whose match-type scrutinees are only data (the slot tuple,
destructured elements, literal parameters) — `bench.RuleCostProbe` measures why that encoding
wins (a match-type verdict caches across a file, its one-time reduction cost is set by what
sits in scrutinee position, and nested scrutinees re-reduce without memoization). The `methods`
row is the `commands` interface as command *methods* — flagged `@run` with `Parser.methods`,
against mainargs' `ParserForMethods` (its `commands` entry is already that encoding, so its two
rows measure the same source; case-app has no method-based API, so its entry reuses the
command-objects encoding).

### Scaling with field count

`bench.ScalingProbe` (same warm driver, best of five, one options class of N `Int` fields;
`flagged@` has every second field `@name`-annotated):

| n fields | flagged | flagged@ | mainargs |
|---|---|---|---|
| 4 | 121 | 120 | 230 |
| 8 | 119 | 123 | 230 |
| 16 | 119 | 138 | 223 |
| 32 | 139 | 171 | 236 |
| 64 | 190 | 267 | 251 |
| 128 | 312 | 549 | 284 |

Marginal cost is roughly 1.6 ms per unannotated field (~3.4 ms with half the fields annotated)
and approximately constant across the range — compile time grows linearly with field count.
flagged is ahead of mainargs up to and including 64 fields and ~10% behind at 128. A 64-field
annotated class compiles at the default `-Xmax-inlines`. A JFR profile of the looped driver
(`bench.ProfileProbe`) shows no single hotspot — implicit search and match-type reduction are
negligible — and `bench.AblationProbe` breaks derivation cost down by component.

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
| `empty` — µs/op | 0.035 ± 0.001 | 0.151 ± 0.001 | 0.081 ± 0.001 |
| `empty` — B/op | 208 | 1 240 | 536 |
| `simple` — µs/op | 0.119 ± 0.006 | 0.989 ± 0.011 | 1.444 ± 0.048 |
| `simple` — B/op | 480 | 5 435 | 9 182 |
| `repeated` — µs/op | 0.161 ± 0.007 | 0.988 ± 0.010 | 4.786 ± 0.192 |
| `repeated` — B/op | 680 | 5 242 | 27 170 |

### flagged × mainargs (short clusters, typed leftover, `Map[K,V]`)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.134 ± 0.023 | 1.210 ± 0.057 |
| `bundled` — B/op | 520 | 6 894 |
| `leftover` — µs/op | 0.156 ± 0.003 | 0.305 ± 0.002 |
| `leftover` — B/op | 736 | 2 918 |
| `map` — µs/op | 0.312 ± 0.022 | 0.542 ± 0.010 |
| `map` — B/op | 1 030 | 3 933 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.086 ± 0.001 | 2.027 ± 0.082 |
| `counter` — B/op | 456 | 12 762 |
| `group` — µs/op | 0.213 ± 0.027 | 3.372 ± 0.072 |
| `group` — B/op | 640 | 18 803 |

### `realistic` — a docker-style CLI, wider field

`realistic` is modeled on a subset of `docker` `run`/`pull`/`ps` (github.com/docker/cli,
Apache-2.0): one subcommand level, a 20-field `run` command with mostly optional and four
repeatable options, parsed from a 34-token command line. Beyond the three derivation
libraries, this scenario also measures the most-used builder/DSL/reflection libraries — scopt,
scallop, and picocli (the Java standard) — at runtime only, since they have no derivation to
measure at compile time. Each library uses its idiomatic subcommand encoding (definitions in
`bench-portable/src/defs/RealisticDefs.scala` and `bench/src/RealisticJvmDefs.scala`). Setup
asserts all six agree on every parsed field. All rows below are from one run:

| Library | µs/op | B/op |
|---|---|---|
| flagged | 0.701 ± 0.005 | 2 187 |
| mainargs | 9.935 ± 0.114 | 34 605 |
| scopt 4.1.0 | 56.856 ± 2.098 | 90 471 |
| picocli 4.7.6 | 64.399 ± 0.484 | 89 583 |
| case-app | 66.588 ± 1.097 | 391 084 |
| scallop 5.1.0 | 162.530 ± 1.198 | 472 401 |

flagged parses this line 14× faster than mainargs and 81–232× faster than the rest,
allocating 16–216× less. The non-derivation libraries pay for their models at parse time:
scopt copies its 30-field config on every action, picocli walks its reflective model (built
once in setup — `parseArgs` resets and repopulates the annotated fields), and scallop
constructs and verifies the whole `ScallopConf` per argument list, which is its usage model —
definition and parse are coupled, so parser construction is part of every parse.

On non-trivial argument lists flagged parses in 0.09–0.70 µs across all scenarios, 1.7–95×
faster than mainargs and case-app on the same inputs, allocating 3.8–179× less — the remaining
bytes are the parse's actual output (the config object, `Some` wrappers for declared Option
fields, value substrings of `=`-forms and `k=v` entries) plus one set of per-parse state
arrays. The closest contests are mainargs' `Map` (1.7×) and `Leftover` (2.0×), whose per-token
work is already minimal.

### Method-based commands (flagged × mainargs)

`bench.MethodBench` measures the method-parser path — the two libraries where commands are
annotated methods and a successful parse *invokes* one. `method` is a lone command method with
the `simple` grammar (`--foo hello --bar 42 --baz`); `commands` dispatches on the first token of
`add core --url https://x.git` across three methods. Setup asserts both libraries return the
same invoked result.

| Scenario | flagged | mainargs |
|---|---|---|
| `method` — µs/op | 0.098 ± 0.001 | 0.984 ± 0.032 |
| `method` — B/op | 432 | 5 738 |
| `commands` — µs/op | 0.097 ± 0.002 | 0.466 ± 0.004 |
| `commands` — B/op | 720 | 3 536 |

flagged parses-and-invokes 4.8–10× faster than mainargs, allocating 5–13× less. Method
commands cost flagged no more than its class derivation — `method` at 0.098 µs against the
class-based `simple` scenario's 0.119 µs on the same grammar — the invoker being a direct call
with one cast per argument where class derivation constructs a case class. mainargs' `commands`
run is *faster* than its lone-method run because the chosen `add` command parses two parameters
instead of four.

### Against hand-written parsers and `@main`

`bench.BaselineBench` parses the same argument lists with non-library baselines: the *typical*
quick hand-rolled parser (a tail-recursive match over the token list with exact-string patterns
and a copied accumulator — long options only, no `=` forms, first error wins), a
*feature-parity* hand-rolled parser (short options, clusters, attached values, `--opt=v`,
last-wins, error accumulation — a mutable cursor loop, ~60 lines for this one fixed grammar),
and Scala's built-in `@main` machinery (`scala.util.CommandLineParser`). `wide25` scales the
typical idiom to 25 named `String` options, all provided; `positional` and `positional25` are
`@main`'s fair comparisons — `@main` has no named options, so flagged parses the same tokens
as all-`@positional` fields, the one grammar both can express.

| Scenario | flagged | typical hand-rolled | feature-parity | `@main` |
|---|---|---|---|---|
| `empty` — µs/op | 0.035 | 0.003 | 0.003 | |
| `empty` — B/op | 208 | 48 | 48 | |
| `simple` — µs/op | 0.119 | 0.015 | 0.038 | |
| `simple` — B/op | 480 | 144 | 48 | |
| `repeated` — µs/op | 0.161 | 0.119 | 0.072 | |
| `repeated` — B/op | 680 | 560 | 144 | |
| `bundled` — µs/op | 0.134 | unsupported | 0.026 | |
| `bundled` — B/op | 520 | unsupported | 96 | |
| `wide25` — µs/op | 0.576 | 1.443 | | |
| `wide25` — B/op | 656 | 2 928 | | |
| `positional` — µs/op | 0.075 | | | 0.005 |
| `positional` — B/op | 472 | | | 32 |
| `positional25` — µs/op | 0.258 | | | 0.016 |
| `positional25` — B/op | 496 | | | 112 |

Hand-written parsers assign straight into locals or a small accumulator of the known types, so
they carry none of the generic machinery — no per-parse state arrays sized by the command's
arity, no erased value slots, no `Mirror`-based construction, no dispatch through parser
instances, and no help/suggestion/subcommand plumbing reachable from the hot path. That
machinery costs flagged ~80–110 ns and ~420–540 B per parse over the feature-parity baseline
(2.2–5.2×). Against the typical parser the gap is 7.9× on `simple`, narrows to 1.4× on
`repeated` (the accumulator `copy` per token and `:+` append cost nearly what the whole engine
does), and inverts at `wide25`: the match chain tests up to 25 exact strings per token and
copies a 25-field accumulator per option, ending up 2.5× slower and 4.5× more allocating than
the engine's per-token hash lookup and value slots — and the idiom's cost grows with
options × tokens where the engine's grows with tokens.

`@main` remains ~15–16× faster on the positional grammars — sequential typed reads with no
option routing, no error accumulation, and no help — but positionals are all it can express,
and errors are thrown, not reported.

### Cross-platform (Scala.js, Wasm, Scala Native)

`bench-portable/` re-runs the same scenarios against the same parser definitions on the
platforms JMH cannot cover, with a calibrated best-of-5-rounds timer (~250 ms rounds). The JVM
column below uses the same portable harness for comparability — its closure indirection and
short calibrated rounds add overhead to every library, so the JMH tables above stay canonical
for JVM. Scala.js 1.21.0 on Node 26 (`js`, plus the `jsWasm` module for the WebAssembly
backend), Scala Native 0.5.11 in `release-fast`; the last column is the `nativeMax` module —
the recommended Native release configuration (release-full, thin LTO, no GC).

ns per parse, best of 5 rounds:

| Benchmark | JVM | JS | JS/Wasm | Native | Native (max) |
|---|---|---|---|---|---|
| empty — flagged | 35 | 300 | 164 | 157 | 110 |
| empty — mainargs | 269 | 1 400 | 1 040 | 658 | 499 |
| empty — case-app | 136 | 390 | 262 | 272 | 185 |
| simple — flagged | 195 | 1 034 | 775 | 397 | 294 |
| simple — mainargs | 1 218 | 7 317 | 6 657 | 3 516 | 2 487 |
| simple — case-app | 1 456 | 4 658 | 3 439 | 6 320 | 3 434 |
| repeated — flagged | 158 | 1 130 | 951 | 517 | 390 |
| repeated — mainargs | 1 085 | 7 862 | 6 491 | 3 597 | 2 512 |
| repeated — case-app | 5 045 | 12 509 | 9 688 | 19 047 | 9 718 |
| bundled — flagged | 146 | 903 | 529 | 329 | 253 |
| bundled — mainargs | 1 285 | 8 223 | 6 218 | 4 022 | 2 914 |
| counter — flagged | 93 | 861 | 600 | 358 | 245 |
| counter — case-app | 2 196 | 6 418 | 4 181 | 8 650 | 4 789 |
| group — flagged | 195 | 1 377 | 977 | 545 | 408 |
| group — case-app | 3 467 | 9 611 | 6 524 | 13 205 | 7 105 |
| leftover — flagged | 169 | 1 600 | 713 | 544 | 420 |
| leftover — mainargs | 443 | 2 859 | 1 599 | 1 307 | 1 078 |
| realistic — flagged | 819 | 5 491 | 4 501 | 1 916 | 1 478 |
| realistic — mainargs | 9 879 | 61 631 | 42 910 | 25 424 | 16 698 |
| realistic — case-app | 69 061 | 200 565 | 128 061 | 268 707 | 148 778 |

flagged is the fastest of the three on every scenario on every platform. In the maxed Native
build flagged parses the small scenarios in 0.24–0.42 µs and the realistic docker-style line
in 1.5 µs — roughly 1.5–3× the portable-harness JVM column and ahead of Scala.js by 3–4×. The
WebAssembly backend beats the JavaScript one on every flagged scenario. case-app's `realistic`
parse is slower on Native than on the JVM or JS (0.13–0.27 ms per parse on every platform).

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
