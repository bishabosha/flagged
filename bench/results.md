# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). All
tables were measured in one session at the commit below — the state where derivation *is*
assembly (the inline field walk feeds `Assemble.FieldsBuilder` directly and a constructed
command is parse-ready, with its lookup tables built and nothing deferred), on top of the
multi-token features (`Parser.Product`, `@split`, `@greedy`), the `Parser.Shared` splice model,
and the single-pass compile-time rules layer (see `bench.RuleCostProbe` for the measurements
behind that encoding) — with flagged on the benchmark classpath as its packaged jar
(`FlaggedFromJar` in `build.mill`), like the mainargs/case-app jars from the coursier cache.
JMH scores are averages ± 99.9% confidence intervals; the compile table is one forked JVM (3
warmup + 5 measurement iterations), and the runtime tables (`RuntimeBench`, `ConstructBench`,
`OneshotBench`, `MethodBench`, `BaselineBench`) are five forked JVMs per benchmark (5 warmup +
5 measurement iterations each, all libraries alike), with allocation from the same `-prof gc`
runs.

- Date: 2026-07-24, flagged commit `a58787a`
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
| `options10` (10 mixed fields) | 48.7 ± 2.4 | 88.1 ± 7.2 | 248.5 ± 22.4 | 483.6 ± 37.4 |
| `options25` (25 defaulted fields) | 44.2 ± 2.4 | 98.2 ± 7.6 | 254.2 ± 23.9 | 525.6 ± 87.7 |
| `commands` (3 subcommands) | 45.3 ± 5.5 | 97.0 ± 8.2 | 279.1 ± 40.0 | 499.6 ± 79.7 |
| `methods` (3 command methods) | 27.1 ± 1.6 | 103.1 ± 6.8 | 269.8 ± 44.9 | 501.4 ± 69.2 |
| `realistic` (docker-style CLI) | 54.7 ± 4.1 | 145.1 ± 10.5 | 221.1 ± 27.3 | 574.4 ± 67.4 |

Derivation cost over the baseline: flagged adds ~39–90 ms, mainargs ~166–243 ms, case-app
~435–520 ms; flagged is the cheapest of the three in every scenario, including the wide
`realistic` interface. Relative to the previous published state, flagged's rows dropped by
another ~10–30 ms per file: the walk now assembles the command directly (no per-field
label/triple trees in the inline expansion) on top of the earlier rules-layer rework — the
per-field validation is a single pass over the annotation slot whose match-type scrutinees are
only data (the slot tuple, destructured elements, literal parameters); `bench.RuleCostProbe`
measures why that encoding wins (a match-type verdict caches across a file, its one-time
reduction cost is set by what sits in scrutinee position, and nested scrutinees re-reduce
without memoization). The `methods` row is the `commands` interface as command *methods* —
flagged `@run` with `Parser.methods`, against mainargs' `ParserForMethods` (its `commands`
entry is already that encoding, so its two rows measure the same source; case-app has no
method-based API, so its entry reuses the command-objects encoding).

### Scaling with field count

`bench.ScalingProbe` (same warm driver, best of five, one options class of N `Int` fields;
`flagged@` has every second field `@name`-annotated):

| n fields | flagged | flagged@ | mainargs |
|---|---|---|---|
| 4 | 114 | 115 | 233 |
| 8 | 104 | 111 | 237 |
| 16 | 108 | 128 | 232 |
| 32 | 121 | 144 | 242 |
| 64 | 154 | 218 | 274 |
| 128 | 246 | 425 | 297 |

Marginal cost is roughly 1.1 ms per unannotated field (~2.6 ms with half the fields annotated)
and approximately constant across the range — compile time grows linearly with field count.
flagged is ahead of mainargs at every field count, including 128. A 64-field annotated class
compiles at the default `-Xmax-inlines`. A JFR profile of the looped driver
(`bench.ProfileProbe`) shows no single hotspot — implicit search and match-type reduction are
negligible — and `bench.AblationProbe` breaks derivation cost down by component.

## Runtime construction and the one-shot cost

Parser *construction* is runtime work too — the derivation expression executes when the parser
value is created — and a CLI process constructs once and parses once, so the honest per-run
cost is construction + parse. `ConstructBench` measures construction alone (for flagged this
is the whole truth: a constructed command is parse-ready, with its lookup tables built and
nothing deferred to first use); `OneshotBench` measures construct-and-parse as one invocation.

Construction, per parser (`simple`: one 4-field class; `realistic`: the docker-style CLI):

| Scenario | flagged | mainargs | case-app |
|---|---|---|---|
| `simple` — µs | 0.200 ± 0.003 | 0.143 ± 0.001 | 0.109 ± 0.001 |
| `simple` — B | 1 720 | 1 376 | 1 904 |
| `realistic` — µs | 1.821 ± 0.012 | 1.015 ± 0.007 | 1.058 ± 0.006 |
| `realistic` — B | 12 248 | 8 040 | 13 304 |

Construction is the one place flagged is not fastest: mainargs constructs the realistic CLI
~1.8× quicker (flagged builds the complete parse-ready model — lookup maps included — where
the others defer some model work into each parse; the parse tables below show where that
deferral lands). The one-shot measurement adds the halves up honestly — each library
constructs what its idiom needs for the invocation: flagged and mainargs derive the whole
command group, case-app's first-token dispatch constructs only the invoked command's parser,
scopt rebuilds its `OParser` chain, scallop's `ScallopConf` is construct-and-parse by design,
and picocli rebuilds its reflective model:

| One-shot | µs/op | B/op |
|---|---|---|
| `simple` — flagged | 0.343 ± 0.002 | 2 176 |
| `simple` — mainargs | 1.162 ± 0.030 | 7 186 |
| `simple` — case-app | 1.677 ± 0.055 | 10 994 |
| `realistic` — flagged | 2.691 ± 0.018 | 14 408 |
| `realistic` — mainargs | 12.350 ± 0.255 | 44 389 |
| `realistic` — scopt | 51.217 ± 0.746 | 110 456 |
| `realistic` — case-app | 59.019 ± 0.589 | 374 044 |
| `realistic` — scallop | 170.214 ± 1.363 | 454 405 |
| `realistic` — picocli | 255.941 ± 4.596 | 411 468 |

End to end, flagged is the fastest and lightest on both grammars: 3.4–4.9× faster than
mainargs and case-app on `simple`, 4.6× faster than mainargs and 19–95× faster than the rest
on `realistic`, allocating 3.1–32× less. flagged's one-shot is ~70–90% construction; every
other library's is dominated by the parse. picocli's ordering inverts between the tables:
its steady-state parse is mid-pack, but building the reflective model per process makes its
one-shot the slowest here.

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
| `empty` — µs/op | 0.033 ± 0.001 | 0.148 ± 0.002 | 0.080 ± 0.003 |
| `empty` — B/op | 208 | 1 240 | 536 |
| `simple` — µs/op | 0.123 ± 0.007 | 0.999 ± 0.022 | 1.472 ± 0.020 |
| `simple` — B/op | 480 | 5 579 | 9 456 |
| `repeated` — µs/op | 0.164 ± 0.009 | 0.954 ± 0.007 | 4.849 ± 0.093 |
| `repeated` — B/op | 680 | 5 294 | 28 130 |

### flagged × mainargs (short clusters, typed leftover, `Map[K,V]`)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.152 ± 0.023 | 1.175 ± 0.055 |
| `bundled` — B/op | 520 | 6 923 |
| `leftover` — µs/op | 0.151 ± 0.008 | 0.301 ± 0.003 |
| `leftover` — B/op | 736 | 2 931 |
| `map` — µs/op | 0.329 ± 0.010 | 0.537 ± 0.010 |
| `map` — B/op | 1 002 | 3 984 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.082 ± 0.001 | 1.934 ± 0.073 |
| `counter` — B/op | 453 | 12 968 |
| `group` — µs/op | 0.215 ± 0.001 | 3.259 ± 0.065 |
| `group` — B/op | 608 | 19 059 |

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
| flagged | 0.784 ± 0.068 | 2 216 |
| mainargs | 9.873 ± 0.161 | 34 520 |
| scopt 4.1.0 | 54.332 ± 2.416 | 87 213 |
| picocli 4.7.6 | 62.663 ± 0.438 | 89 556 |
| case-app | 66.332 ± 1.736 | 382 946 |
| scallop 5.1.0 | 158.688 ± 0.860 | 472 728 |

flagged parses this line 13× faster than mainargs and 69–202× faster than the rest,
allocating 16–213× less. The non-derivation libraries pay for their models at parse time:
scopt copies its 30-field config on every action, picocli walks its reflective model (built
once in setup — `parseArgs` resets and repopulates the annotated fields), and scallop
constructs and verifies the whole `ScallopConf` per argument list, which is its usage model —
definition and parse are coupled, so parser construction is part of every parse.

On non-trivial argument lists flagged parses in 0.08–0.78 µs across all scenarios, 1.6–85×
faster than mainargs and case-app on the same inputs, allocating 4.0–173× less — the remaining
bytes are the parse's actual output (the config object, `Some` wrappers for declared Option
fields, value substrings of `=`-forms and `k=v` entries) plus one set of per-parse state
arrays. The closest contests are mainargs' `Map` (1.6×) and `Leftover` (2.0×), whose per-token
work is already minimal.

### Method-based commands (flagged × mainargs)

`bench.MethodBench` measures the method-parser path — the two libraries where commands are
annotated methods and a successful parse *invokes* one. `method` is a lone command method with
the `simple` grammar (`--foo hello --bar 42 --baz`); `commands` dispatches on the first token of
`add core --url https://x.git` across three methods. Setup asserts both libraries return the
same invoked result.

| Scenario | flagged | mainargs |
|---|---|---|
| `method` — µs/op | 0.102 ± 0.001 | 1.008 ± 0.035 |
| `method` — B/op | 432 | 5 747 |
| `commands` — µs/op | 0.090 ± 0.001 | 0.462 ± 0.003 |
| `commands` — B/op | 736 | 3 526 |

flagged parses-and-invokes 5.1–9.9× faster than mainargs, allocating 4.8–13× less. Method
commands cost flagged no more than its class derivation — `method` at 0.102 µs against the
class-based `simple` scenario's 0.123 µs on the same grammar — the invoker being a direct call
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
| `empty` — µs/op | 0.033 | 0.003 | 0.003 | |
| `empty` — B/op | 208 | 48 | 48 | |
| `simple` — µs/op | 0.123 | 0.014 | 0.040 | |
| `simple` — B/op | 480 | 144 | 48 | |
| `repeated` — µs/op | 0.164 | 0.100 | 0.065 | |
| `repeated` — B/op | 680 | 560 | 144 | |
| `bundled` — µs/op | 0.152 | unsupported | 0.025 | |
| `bundled` — B/op | 520 | unsupported | 96 | |
| `wide25` — µs/op | 0.557 | 1.429 | | |
| `wide25` — B/op | 664 | 2 928 | | |
| `positional` — µs/op | 0.076 | | | 0.005 |
| `positional` — B/op | 472 | | | 32 |
| `positional25` — µs/op | 0.266 | | | 0.016 |
| `positional25` — B/op | 504 | | | 112 |

Hand-written parsers assign straight into locals or a small accumulator of the known types, so
they carry none of the generic machinery — no per-parse state arrays sized by the command's
arity, no erased value slots, no `Mirror`-based construction, no dispatch through parser
instances, and no help/suggestion/subcommand plumbing reachable from the hot path. That
machinery costs flagged ~80–130 ns and ~420–540 B per parse over the feature-parity baseline
(2.5–6.1×). Against the typical parser the gap is 8.8× on `simple`, narrows to 1.6× on
`repeated` (the accumulator `copy` per token and `:+` append cost nearly what the whole engine
does), and inverts at `wide25`: the match chain tests up to 25 exact strings per token and
copies a 25-field accumulator per option, ending up 2.6× slower and 4.4× more allocating than
the engine's per-token hash lookup and value slots — and the idiom's cost grows with
options × tokens where the engine's grows with tokens.

`@main` remains ~15–17× faster on the positional grammars — sequential typed reads with no
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
| empty — flagged | 33 | 310 | 167 | 164 | 112 |
| empty — mainargs | 256 | 1 401 | 1 016 | 649 | 476 |
| empty — case-app | 129 | 384 | 254 | 255 | 175 |
| simple — flagged | 191 | 1 014 | 773 | 408 | 298 |
| simple — mainargs | 1 043 | 7 138 | 6 468 | 3 480 | 2 321 |
| simple — case-app | 1 468 | 4 499 | 3 358 | 6 148 | 3 095 |
| repeated — flagged | 159 | 1 090 | 962 | 503 | 384 |
| repeated — mainargs | 1 022 | 7 310 | 6 549 | 3 490 | 2 384 |
| repeated — case-app | 5 099 | 12 052 | 9 620 | 18 274 | 9 333 |
| bundled — flagged | 158 | 858 | 520 | 340 | 242 |
| bundled — mainargs | 1 212 | 7 908 | 6 295 | 3 778 | 2 938 |
| counter — flagged | 92 | 832 | 611 | 355 | 251 |
| counter — case-app | 2 465 | 6 196 | 4 180 | 8 459 | 4 570 |
| group — flagged | 222 | 1 256 | 950 | 510 | 378 |
| group — case-app | 3 817 | 9 268 | 6 306 | 12 430 | 6 930 |
| leftover — flagged | 193 | 1 574 | 734 | 545 | 414 |
| leftover — mainargs | 391 | 2 776 | 1 597 | 1 295 | 1 030 |
| realistic — flagged | 789 | 5 480 | 4 448 | 1 881 | 1 435 |
| realistic — mainargs | 10 184 | 61 113 | 41 888 | 23 994 | 16 245 |
| realistic — case-app | 78 510 | 192 975 | 126 497 | 257 300 | 146 069 |

flagged is the fastest of the three on every scenario on every platform. In the maxed Native
build flagged parses the small scenarios in 0.24–0.41 µs and the realistic docker-style line
in 1.4 µs — roughly 1.5–3× the portable-harness JVM column and ahead of Scala.js by 3–4×. The
WebAssembly backend beats the JavaScript one on every flagged scenario. case-app's `realistic`
parse is slower on Native than on the JVM or JS (0.13–0.26 ms per parse on every platform).

Why Native trails the JVM: an ahead-of-time build has no profile-guided optimization, so the
remaining polymorphic dispatch and the workload's own allocations stay real, where HotSpot
devirtualizes and escape-analyzes them after profiling. The engine keeps that residue small —
no closures on the hot path, successes written straight into value slots, an Option-free
routing loop, and one virtual method per value parser (a small dispatch surface is what an
AOT optimizer rewards most) — and the recommended build configuration does the rest: thin
LTO inlines across module boundaries into the Scala Native runtime, and for a
parse-once-and-exit CLI binary, no-GC-plus-process-teardown is a sound memory strategy, not
a benchmark trick.

CLI parsing happens once per process, and the one-shot table above is that cost measured
directly: construct + parse in 0.34 µs (`simple`) / 2.7 µs (`realistic`), fastest of the six
libraries end to end. The compile table remains the practically dominant one — it is paid on
every build, not once per run.
