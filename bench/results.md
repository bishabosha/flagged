# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). All
tables were measured in one session at the commit below — the state where derivation *is*
assembly (the inline field walk feeds `Assemble.FieldsBuilder` directly and a constructed
command is parse-ready, with its lookup tables built and nothing deferred) and the engine
builds commands into destination slots: per-level parse frames, compacted per-parse state
(seen-bit words plus lazily allocated side arrays), and defaults and `@cmd` execution deferred
until the whole selected command chain has validated — on top of the multi-token features
(`Parser.Product`, `@opt(split)`, `@opt(greedy)`), the `Parser.Shared` splice model, and the single-pass
compile-time rules layer (see `bench.RuleCostProbe` for the measurements behind that encoding)
— with flagged on the benchmark classpath as its packaged jar (`FlaggedFromJar` in
`build.mill`), like the mainargs/case-app jars from the coursier cache. JMH scores are
averages ± 99.9% confidence intervals; the compile table is one forked JVM (3 warmup + 5
measurement iterations), and the runtime tables (`RuntimeBench`, `ConstructBench`,
`OneshotBench`, `MethodBench`, `BaselineBench`) are five forked JVMs per benchmark (5 warmup +
5 measurement iterations each, all libraries alike), with allocation from the same `-prof gc`
runs.

- Date: 2026-07-25, flagged commit `64fc17f`
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
| `options10` (10 mixed fields) | 48.6 ± 2.7 | 91.4 ± 8.7 | 245.9 ± 26.0 | 498.6 ± 24.8 |
| `options25` (25 defaulted fields) | 43.8 ± 2.1 | 99.2 ± 6.2 | 251.2 ± 33.3 | 545.2 ± 40.2 |
| `commands` (3 subcommands) | 45.2 ± 4.0 | 99.1 ± 8.4 | 273.4 ± 26.9 | 531.0 ± 117.7 |
| `methods` (3 command methods) | 27.1 ± 1.5 | 106.5 ± 7.8 | 268.2 ± 25.5 | 511.6 ± 73.2 |
| `realistic` (docker-style CLI) | 53.7 ± 3.7 | 147.8 ± 7.1 | 218.3 ± 19.8 | 579.5 ± 37.7 |

Derivation cost over the baseline: flagged adds ~43–94 ms, mainargs ~165–241 ms, case-app
~450–526 ms; flagged is the cheapest of the three in every scenario, including the wide
`realistic` interface. The per-field validation is a single pass over the annotation slot whose match-type
scrutinees are only data (the slot tuple, destructured elements, literal parameters);
`bench.RuleCostProbe` measures why that encoding wins (a match-type verdict caches across a
file, its one-time reduction cost is set by what sits in scrutinee position, and nested
scrutinees re-reduce without memoization). The `methods` row is the `commands` interface as
command *methods* — flagged `@cmd` with `Parser.methods`, against mainargs'
`ParserForMethods` (its `commands` entry is already that encoding, so its two rows measure
the same source; case-app has no method-based API, so its entry reuses the command-objects
encoding).

### Scaling with field count

`bench.ScalingProbe` (same warm driver, best of five, one options class of N `Int` fields;
`flagged@` has every second field `@opt(name)`-annotated):

| n fields | flagged | flagged@ | mainargs |
|---|---|---|---|
| 4 | 115 | 115 | 238 |
| 8 | 107 | 115 | 235 |
| 16 | 107 | 131 | 247 |
| 32 | 126 | 154 | 250 |
| 64 | 159 | 221 | 252 |
| 128 | 250 | 443 | 299 |

Marginal cost is roughly 1.2 ms per unannotated field (~2.7 ms with half the fields annotated)
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
| `simple` — µs | 0.168 ± 0.001 | 0.142 ± 0.001 | 0.108 ± 0.001 |
| `simple` — B | 1 544 | 1 376 | 1 904 |
| `realistic` — µs | 1.523 ± 0.011 | 1.017 ± 0.006 | 1.057 ± 0.005 |
| `realistic` — B | 12 040 | 8 040 | 13 304 |

Construction is the one place flagged is not fastest: mainargs constructs the realistic CLI
~1.5× quicker (flagged builds the complete parse-ready model — lookup maps included — where
the others defer some model work into each parse; the parse tables below show where that
deferral lands). The one-shot
measurement adds the halves up honestly — each library constructs what its idiom needs for
the invocation: flagged and mainargs derive the whole command group, case-app's first-token
dispatch constructs only the invoked command's parser, scopt rebuilds its `OParser` chain,
scallop's `ScallopConf` is construct-and-parse by design, and picocli rebuilds its reflective
model:

| One-shot | µs/op | B/op |
|---|---|---|
| `simple` — flagged | 0.346 ± 0.009 | 1 984 |
| `simple` — mainargs | 1.193 ± 0.012 | 7 344 |
| `simple` — case-app | 1.637 ± 0.074 | 10 941 |
| `realistic` — flagged | 2.309 ± 0.027 | 14 186 |
| `realistic` — mainargs | 12.679 ± 0.142 | 44 837 |
| `realistic` — scopt | 52.367 ± 1.844 | 115 560 |
| `realistic` — case-app | 60.353 ± 1.115 | 374 044 |
| `realistic` — scallop | 167.991 ± 0.798 | 455 267 |
| `realistic` — picocli | 257.457 ± 1.634 | 410 407 |

End to end, flagged is the fastest and lightest on both grammars: 3.4–4.7× faster than
mainargs and case-app on `simple`, 5.5× faster than mainargs and 23–112× faster than the rest
on `realistic`, allocating 3.2–32× less. flagged's one-shot is ~50–65% construction; every
other library's is dominated by the parse. picocli's ordering inverts between the tables:
its steady-state parse is mid-pack, but building the reflective model per process makes its
one-shot the slowest here.

## Parse latency and allocation

Time and bytes allocated per successful parse (`gc.alloc.rate.norm`), parser instances built
once in setup. The engine's hot path allocates nothing per token: parsers write successes into
value slots (`Result.done` signalling, no `Ok` boxing), per-slot state is seen-bit words with
lazily allocated side arrays, lookups return null instead of `Option`, a long token is its own
display spelling, and no string is created unless it is part of an error being reported.
Function values appear only where user code supplies them (`Parser.of`, `emap`, custom
combinators).

A CLI process pays the one-shot cost above; the steady-state numbers below are what a loop
re-parsing with a prebuilt parser would see.

### Scenarios all three libraries support

| Scenario | flagged | mainargs | case-app |
|---|---|---|---|
| `empty` — µs/op | 0.063 ± 0.003 | 0.147 ± 0.001 | 0.078 ± 0.001 |
| `empty` — B/op | 240 | 1 240 | 536 |
| `simple` — µs/op | 0.238 ± 0.008 | 0.989 ± 0.023 | 1.417 ± 0.051 |
| `simple` — B/op | 440 | 5 475 | 9 336 |
| `repeated` — µs/op | 0.271 ± 0.016 | 0.992 ± 0.045 | 4.858 ± 0.095 |
| `repeated` — B/op | 576 | 5 318 | 28 066 |

### flagged × mainargs (short clusters, typed leftover, `Map[K,V]`)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.227 ± 0.009 | 1.128 ± 0.050 |
| `bundled` — B/op | 480 | 6 816 |
| `leftover` — µs/op | 0.161 ± 0.001 | 0.298 ± 0.002 |
| `leftover` — B/op | 600 | 2 928 |
| `map` — µs/op | 0.309 ± 0.005 | 0.537 ± 0.009 |
| `map` — B/op | 904 | 4 032 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.205 ± 0.007 | 1.949 ± 0.064 |
| `counter` — B/op | 424 | 13 078 |
| `group` — µs/op | 0.271 ± 0.008 | 3.256 ± 0.045 |
| `group` — B/op | 496 | 19 171 |

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
| flagged | 0.779 ± 0.007 | 2 112 |
| mainargs | 9.923 ± 0.093 | 34 546 |
| scopt 4.1.0 | 54.911 ± 2.634 | 87 285 |
| picocli 4.7.6 | 62.768 ± 0.318 | 89 370 |
| case-app | 64.231 ± 2.092 | 388 845 |
| scallop 5.1.0 | 158.870 ± 1.166 | 472 534 |

flagged parses this line 13× faster than mainargs and 70–204× faster than the rest,
allocating 16–224× less. The non-derivation libraries pay for their models at parse time:
scopt copies its 30-field config on every action, picocli walks its reflective model (built
once in setup — `parseArgs` resets and repopulates the annotated fields), and scallop
constructs and verifies the whole `ScallopConf` per argument list, which is its usage model —
definition and parse are coupled, so parser construction is part of every parse.

On non-trivial argument lists flagged parses in 0.16–0.78 µs across all scenarios, 1.7–82×
faster than mainargs and case-app on the same inputs, allocating 4.5–184× less — the remaining
bytes are the parse's actual output (the config object, `Some` wrappers for declared Option
fields, value substrings of `=`-forms and `k=v` entries) plus one set of per-parse state
arrays. The closest contests are mainargs' `Map` (1.7×) and `Leftover` (1.9×), whose per-token
work is already minimal.

### Method-based commands (flagged × mainargs)

`bench.MethodBench` measures the method-parser path — the two libraries where commands are
annotated methods and a successful parse *invokes* one. `method` is a lone command method with
the `simple` grammar (`--foo hello --bar 42 --baz`); `commands` dispatches on the first token of
`add core --url https://x.git` across three methods. Setup asserts both libraries return the
same invoked result.

| Scenario | flagged | mainargs |
|---|---|---|
| `method` — µs/op | 0.233 ± 0.007 | 0.987 ± 0.058 |
| `method` — B/op | 408 | 5 794 |
| `commands` — µs/op | 0.141 ± 0.004 | 0.461 ± 0.004 |
| `commands` — B/op | 488 | 3 531 |

flagged parses-and-invokes 3.3–4.2× faster than mainargs, allocating 7.2–14× less. Method
commands cost flagged no more than its class derivation — `method` at 0.233 µs against the
class-based `simple` scenario's 0.238 µs on the same grammar — the invoker being a direct call
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
as all-positional fields, the one grammar both can express.

| Scenario | flagged | typical hand-rolled | feature-parity | `@main` |
|---|---|---|---|---|
| `empty` — µs/op | 0.063 | 0.003 | 0.003 | |
| `empty` — B/op | 240 | 48 | 48 | |
| `simple` — µs/op | 0.238 | 0.014 | 0.038 | |
| `simple` — B/op | 440 | 144 | 48 | |
| `repeated` — µs/op | 0.271 | 0.107 | 0.067 | |
| `repeated` — B/op | 576 | 560 | 150 | |
| `bundled` — µs/op | 0.227 | unsupported | 0.025 | |
| `bundled` — B/op | 480 | unsupported | 96 | |
| `wide25` — µs/op | 0.681 | 1.416 | | |
| `wide25` — B/op | 512 | 2 928 | | |
| `positional` — µs/op | 0.108 | | | 0.005 |
| `positional` — B/op | 368 | | | 32 |
| `positional25` — µs/op | 0.268 | | | 0.016 |
| `positional25` — B/op | 392 | | | 112 |

Hand-written parsers assign straight into locals or a small accumulator of the known types, so
they carry none of the generic machinery — no per-parse state sized by the command's arity, no
erased value slots, no `Mirror`-based construction, no dispatch through parser instances, and
no help/suggestion/subcommand plumbing reachable from the hot path. That machinery costs
flagged ~60–200 ns and ~190–430 B per parse over the feature-parity baseline (4.0–21×).
Against the typical parser the gap is 17× on `simple`, narrows to 2.5× on `repeated` (the
accumulator `copy` per token and `:+` append cost nearly what the whole engine does), and
inverts at `wide25`: the match chain tests up to 25 exact strings per token and copies a
25-field accumulator per option, ending up 2.1× slower and 5.7× more allocating than the
engine's per-token hash lookup and value slots — and the idiom's cost grows with
options × tokens where the engine's grows with tokens.

`@main` remains ~17–22× faster on the positional grammars — sequential typed reads with no
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
| empty — flagged | 63 | 301 | 227 | 159 | 116 |
| empty — mainargs | 238 | 1 345 | 1 046 | 648 | 505 |
| empty — case-app | 133 | 376 | 265 | 258 | 201 |
| simple — flagged | 183 | 946 | 819 | 433 | 328 |
| simple — mainargs | 1 226 | 7 159 | 7 255 | 3 483 | 2 768 |
| simple — case-app | 1 447 | 4 537 | 3 497 | 5 978 | 3 739 |
| repeated — flagged | 223 | 1 258 | 981 | 540 | 448 |
| repeated — mainargs | 1 045 | 7 292 | 6 773 | 3 525 | 2 950 |
| repeated — case-app | 5 149 | 11 860 | 9 944 | 18 042 | 10 113 |
| bundled — flagged | 211 | 862 | 622 | 352 | 261 |
| bundled — mainargs | 1 295 | 7 966 | 6 299 | 3 831 | 2 771 |
| counter — flagged | 192 | 829 | 662 | 369 | 251 |
| counter — case-app | 2 305 | 6 226 | 4 173 | 8 288 | 4 728 |
| group — flagged | 254 | 1 261 | 981 | 519 | 373 |
| group — case-app | 3 552 | 9 357 | 6 307 | 12 392 | 6 732 |
| leftover — flagged | 209 | 1 182 | 771 | 544 | 401 |
| leftover — mainargs | 431 | 2 629 | 1 592 | 1 282 | 1 084 |
| realistic — flagged | 792 | 5 234 | 4 512 | 2 165 | 1 542 |
| realistic — mainargs | 10 499 | 60 039 | 42 199 | 24 014 | 16 403 |
| realistic — case-app | 72 496 | 193 670 | 125 782 | 257 245 | 145 685 |

flagged is the fastest of the three on every scenario on every platform. In the maxed Native
build flagged parses the small scenarios in 0.12–0.45 µs and the realistic docker-style line
in 1.5 µs — roughly 1.2–2× the portable-harness JVM column and ahead of Scala.js by 2.6–3.4×.
The WebAssembly backend beats the JavaScript one on every flagged scenario. case-app's
`realistic` parse is slower on Native than on the JVM or JS (0.07–0.26 ms per parse across
the platforms).

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
directly: construct + parse in 0.35 µs (`simple`) / 2.3 µs (`realistic`), fastest of the six
libraries end to end. The compile table remains the practically dominant one — it is paid on
every build, not once per run.
