# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). All
tables were measured in one session at the commit below — the first state that includes the
multi-token features (`Parser.Product`, `@split`, `@greedy`) and the `Parser.Shared` splice
model, in both the derivation and the engine — with flagged on the benchmark classpath as its
packaged jar (`FlaggedFromJar` in `build.mill`), like the mainargs/case-app jars from the
coursier cache. JMH scores are averages ± 99.9% confidence intervals; the compile table is one
forked JVM (3 warmup + 5 measurement iterations), and the runtime tables (`RuntimeBench`,
`MethodBench`, `BaselineBench`) are five forked JVMs per benchmark (5 warmup + 5 measurement
iterations each, all libraries alike), with allocation from the same `-prof gc` runs. This
session's confidence intervals are wider than the previous published run's; cross-library
comparisons within a row are unaffected.

- Date: 2026-07-23, flagged commit `f1de5a0`
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
| `options10` (10 mixed fields) | 54.7 ± 4.6 | 139.4 ± 25.0 | 234.4 ± 24.7 | 473.2 ± 69.2 |
| `options25` (25 defaulted fields) | 68.3 ± 16.1 | 145.1 ± 11.4 | 245.4 ± 35.3 | 503.3 ± 82.8 |
| `commands` (3 subcommands) | 51.6 ± 6.9 | 134.7 ± 17.9 | 250.1 ± 43.6 | 454.7 ± 64.6 |
| `methods` (3 command methods) | 35.7 ± 22.3 | 135.3 ± 6.1 | 254.9 ± 47.0 | 484.4 ± 99.9 |
| `realistic` (docker-style CLI) | 65.7 ± 19.5 | 213.3 ± 30.2 | 216.2 ± 10.4 | 608.4 ± 573.6 |

Derivation cost over the baseline: flagged adds ~77–148 ms, mainargs ~151–219 ms, case-app
~403–543 ms; flagged is the cheapest of the three in every scenario, though on the wide
`realistic` interface its lead over mainargs has narrowed to the margin of error. Relative to
the pre-feature state, flagged's rows carry the new derivation surface (the tuple `Product`
given in every field's implicit scope, the `@split`/`@greedy` walks, the sum-level rules) at
roughly 10–25 ms per file. The `methods` row is the `commands` interface as command *methods* —
flagged `@run` with `Parser.methods`, against mainargs' `ParserForMethods` (its `commands`
entry is already that encoding, so its two rows measure the same source; case-app has no
method-based API, so its entry reuses the command-objects encoding).

### Scaling with field count

`bench.ScalingProbe` (same warm driver, best of five, one options class of N `Int` fields;
`flagged@` has every second field `@name`-annotated):

| n fields | flagged | flagged@ | mainargs |
|---|---|---|---|
| 4 | 137 | 151 | 239 |
| 8 | 139 | 146 | 227 |
| 16 | 152 | 171 | 255 |
| 32 | 178 | 212 | 263 |
| 64 | 225 | 319 | 262 |
| 128 | 357 | 600 | 300 |

Marginal cost is roughly 1.8 ms per unannotated field (~3.7 ms with half the fields annotated)
and approximately constant across the range — compile time grows linearly with field count.
flagged is ahead of mainargs up to and including 64 fields and ~19% behind at 128. A 64-field
annotated class compiles at the default `-Xmax-inlines`. A JFR profile of the looped driver
(`bench.ProfileProbe`) shows no single hotspot — implicit search and match-type reduction are
negligible — and `bench.AblationProbe` breaks derivation cost down by component.

## Parse latency and allocation

Time and bytes allocated per successful parse (`gc.alloc.rate.norm`), parser instances built
once in setup. The engine's hot path allocates nothing per token: parsers write successes into
value slots (`Result.done` signalling, no `Ok` boxing), per-slot state is primitive arrays,
lookups return null instead of `Option`, a long token is its own display spelling, and no
string is created unless it is part of an error being reported. Function values appear only
where user code supplies them (`Parser.of`, `emap`, custom combinators). The new shapes cost
the existing scenarios a slightly richer mode dispatch (the split/greedy/product arms) and one
flag field on repeated collectors — flagged's rows sit 0.01–0.06 µs and 16–32 B above the
pre-feature state, with the cross-library ordering and magnitudes unchanged.

### Scenarios all three libraries support

| Scenario | flagged | mainargs | case-app |
|---|---|---|---|
| `empty` — µs/op | 0.042 ± 0.010 | 0.167 ± 0.021 | 0.082 ± 0.002 |
| `empty` — B/op | 208 | 1 235 | 536 |
| `simple` — µs/op | 0.128 ± 0.010 | 1.044 ± 0.050 | 1.466 ± 0.059 |
| `simple` — B/op | 477 | 5 446 | 9 573 |
| `repeated` — µs/op | 0.165 ± 0.006 | 1.022 ± 0.045 | 4.757 ± 0.237 |
| `repeated` — B/op | 680 | 5 325 | 26 811 |

### flagged × mainargs (short clusters, typed leftover, `Map[K,V]`)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.129 ± 0.010 | 1.414 ± 0.152 |
| `bundled` — B/op | 517 | 6 995 |
| `leftover` — µs/op | 0.179 ± 0.041 | 0.318 ± 0.011 |
| `leftover` — B/op | 736 | 2 949 |
| `map` — µs/op | 0.325 ± 0.016 | 0.588 ± 0.052 |
| `map` — B/op | 1 030 | 3 890 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.097 ± 0.011 | 2.001 ± 0.101 |
| `counter` — B/op | 456 | 13 310 |
| `group` — µs/op | 0.214 ± 0.024 | 3.135 ± 0.180 |
| `group` — B/op | 640 | 19 459 |

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
| flagged | 0.810 ± 0.039 | 2 190 |
| mainargs | 10.154 ± 0.175 | 34 747 |
| scopt 4.1.0 | 56.655 ± 2.068 | 90 602 |
| picocli 4.7.6 | 64.383 ± 0.425 | 89 506 |
| case-app | 73.436 ± 6.482 | 392 517 |
| scallop 5.1.0 | 162.868 ± 1.016 | 472 527 |

flagged parses this line 13× faster than mainargs and 70–201× faster than the rest,
allocating 16–216× less. The non-derivation libraries pay for their models at parse time:
scopt copies its 30-field config on every action, picocli walks its reflective model (built
once in setup — `parseArgs` resets and repopulates the annotated fields), and scallop
constructs and verifies the whole `ScallopConf` per argument list, which is its usage model —
definition and parse are coupled, so parser construction is part of every parse.

On non-trivial argument lists flagged parses in 0.10–0.81 µs across all scenarios, 1.8–91×
faster than mainargs and case-app on the same inputs, allocating 3.8–179× less — the remaining
bytes are the parse's actual output (the config object, `Some` wrappers for declared Option
fields, value substrings of `=`-forms and `k=v` entries) plus one set of per-parse state
arrays. The closest contests are mainargs' `Map` and `Leftover` (both ~1.8×), whose per-token
work is already minimal.

### Method-based commands (flagged × mainargs)

`bench.MethodBench` measures the method-parser path — the two libraries where commands are
annotated methods and a successful parse *invokes* one. `method` is a lone command method with
the `simple` grammar (`--foo hello --bar 42 --baz`); `commands` dispatches on the first token of
`add core --url https://x.git` across three methods. Setup asserts both libraries return the
same invoked result.

| Scenario | flagged | mainargs |
|---|---|---|
| `method` — µs/op | 0.100 ± 0.001 | 1.006 ± 0.042 |
| `method` — B/op | 432 | 5 757 |
| `commands` — µs/op | 0.098 ± 0.002 | 0.474 ± 0.004 |
| `commands` — B/op | 720 | 3 522 |

flagged parses-and-invokes 4.8–10× faster than mainargs, allocating 5–13× less. Method
commands cost flagged no more than its class derivation — `method` at 0.100 µs against the
class-based `simple` scenario's 0.128 µs on the same grammar — the invoker being a direct call
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
| `empty` — µs/op | 0.042 | 0.004 | 0.003 | |
| `empty` — B/op | 208 | 48 | 48 | |
| `simple` — µs/op | 0.128 | 0.015 | 0.039 | |
| `simple` — B/op | 477 | 144 | 48 | |
| `repeated` — µs/op | 0.165 | 0.120 | 0.075 | |
| `repeated` — B/op | 680 | 560 | 144 | |
| `bundled` — µs/op | 0.129 | unsupported | 0.027 | |
| `bundled` — B/op | 517 | unsupported | 96 | |
| `wide25` — µs/op | 0.587 | 1.454 | | |
| `wide25` — B/op | 656 | 2 928 | | |
| `positional` — µs/op | 0.076 | | | 0.005 |
| `positional` — B/op | 472 | | | 32 |
| `positional25` — µs/op | 0.259 | | | 0.016 |
| `positional25` — B/op | 496 | | | 112 |

Hand-written parsers assign straight into locals or a small accumulator of the known types, so
they carry none of the generic machinery — no per-parse state arrays sized by the command's
arity, no erased value slots, no `Mirror`-based construction, no dispatch through parser
instances, and no help/suggestion/subcommand plumbing reachable from the hot path. That
machinery costs flagged ~90–100 ns and ~420–540 B per parse over the feature-parity baseline
(2.2–4.8×). Against the typical parser the gap is 8.5× on `simple`, narrows to 1.4× on
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
| empty — flagged | 36 | 298 | 169 | 165 | 119 |
| empty — mainargs | 260 | 1 381 | 1 083 | 684 | 518 |
| empty — case-app | 140 | 375 | 268 | 273 | 195 |
| simple — flagged | 201 | 1 045 | 812 | 403 | 312 |
| simple — mainargs | 1 042 | 7 406 | 6 893 | 3 715 | 2 627 |
| simple — case-app | 1 457 | 4 762 | 3 566 | 6 555 | 3 493 |
| repeated — flagged | 154 | 1 117 | 1 041 | 523 | 393 |
| repeated — mainargs | 1 078 | 7 582 | 6 973 | 3 740 | 2 563 |
| repeated — case-app | 5 373 | 12 367 | 10 124 | 19 631 | 10 398 |
| bundled — flagged | 105 | 876 | 528 | 347 | 258 |
| bundled — mainargs | 1 262 | 8 303 | 6 541 | 4 146 | 3 346 |
| counter — flagged | 92 | 851 | 612 | 376 | 266 |
| counter — case-app | 2 315 | 6 501 | 4 295 | 8 932 | 5 019 |
| group — flagged | 191 | 1 383 | 1 000 | 583 | 418 |
| group — case-app | 3 777 | 9 768 | 6 554 | 17 289 | 7 239 |
| leftover — flagged | 165 | 1 622 | 748 | 778 | 416 |
| leftover — mainargs | 417 | 2 948 | 1 653 | 1 361 | 1 129 |
| realistic — flagged | 784 | 5 586 | 4 599 | 2 024 | 1 430 |
| realistic — mainargs | 10 108 | 63 432 | 44 164 | 26 119 | 17 096 |
| realistic — case-app | 72 196 | 207 197 | 130 902 | 279 537 | 151 964 |

flagged is the fastest of the three on every scenario on every platform. In the maxed Native
build flagged parses the small scenarios in 0.26–0.42 µs and the realistic docker-style line
in 1.4 µs — roughly 1.5–3× the portable-harness JVM column and ahead of Scala.js by 3–4×. The
WebAssembly backend beats the JavaScript one on every flagged scenario. case-app's `realistic`
parse is slower on Native than on the JVM or JS (0.13–0.28 ms per parse on every platform).

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
