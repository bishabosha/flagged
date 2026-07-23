# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). All
tables were measured on the date below at the commit below — the base tables in one session,
the `realistic` rows and the cross-platform table in a follow-up run the same day after the
`realistic` scenario was added (library sources unchanged in between) — with flagged on the
benchmark classpath as its packaged jar (`FlaggedFromJar` in `build.mill`), like the
mainargs/case-app jars from the coursier cache. JMH scores are averages ± 99.9% confidence intervals; the compile
table is one forked JVM (3 warmup + 5 measurement iterations), and the runtime tables
(`RuntimeBench`, `MethodBench`, `BaselineBench`) are five forked JVMs per benchmark
(5 warmup + 5 measurement iterations each, all libraries alike), with allocation from the same
`-prof gc` runs — single-fork scores on the smallest scenarios vary 10–30% with the fork's JIT
inlining plan, so every library gets the same five-fork aggregation.

- Date: 2026-07-23, flagged commit `05bcc6e`, benchmark harness at `dde4bb5`
  (`FlaggedFromJar`, `nativeMax`)
- Hardware: Apple M3 Max, 64 GB, macOS 26.5.1
- JVM: Temurin OpenJDK 25.0.2, Scala 3.8.3, JMH 1.37
- Library versions: mainargs 0.7.8, case-app 2.1.0

## Compile time

Milliseconds to compile one generated source file with a warm in-process `dotty` driver.
`baseline` is the same declarations with no parsing library — the compiler's floor for the file
shape. Only comparisons within a row are meaningful.

| Scenario | baseline | flagged | mainargs | case-app |
|---|---|---|---|---|
| `options10` (10 mixed fields) | 49.9 ± 2.8 | 105.5 ± 13.9 | 238.7 ± 30.4 | 445.7 ± 8.6 |
| `options25` (25 defaulted fields) | 45.4 ± 2.6 | 124.1 ± 3.4 | 238.7 ± 36.0 | 471.2 ± 16.6 |
| `commands` (3 subcommands) | 48.6 ± 7.7 | 115.0 ± 24.0 | 241.4 ± 20.9 | 463.4 ± 32.5 |
| `methods` (3 command methods) | 27.6 ± 1.4 | 122.2 ± 7.8 | 241.9 ± 22.2 | 464.0 ± 91.1 |
| `realistic` (docker-style CLI) | 57.8 ± 6.1 | 181.1 ± 14.7 | 206.4 ± 12.9 | 497.0 ± 43.2 |

Derivation cost over the baseline: flagged adds ~56–123 ms, mainargs ~149–214 ms, case-app
~396–439 ms; flagged is the cheapest of the three in every scenario, though its linear
per-field cost narrows the gap to mainargs on the wide `realistic` interface (a subcommand
group whose `run` command has 20 fields; see the runtime section). The `methods` row is the
`commands` interface as command *methods* — flagged `@run` with `Parser.methods`, against
mainargs' `ParserForMethods` (its `commands` entry is already that encoding, so its two rows
measure the same source; case-app has no method-based API, so its entry reuses the
command-objects encoding). flagged's method derivation costs about the same as its enum
derivation and stays at roughly half of mainargs'.

### Scaling with field count

`bench.ScalingProbe` (same warm driver, best of five, one options class of N `Int` fields;
`flagged@` has every second field `@name`-annotated):

| n fields | flagged | flagged@ | mainargs |
|---|---|---|---|
| 4 | 129 | 133 | 254 |
| 8 | 139 | 131 | 255 |
| 16 | 133 | 141 | 251 |
| 32 | 149 | 188 | 246 |
| 64 | 207 | 280 | 275 |
| 128 | 330 | 544 | 290 |

Marginal cost is roughly 1.6 ms per unannotated field (~3.3 ms with half the fields annotated)
and approximately constant across the range — compile time grows linearly with field count.
flagged is ahead of mainargs up to and including 64 fields and within ~14% at 128. A 64-field
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
| `empty` — µs/op | 0.034 ± 0.001 | 0.142 ± 0.004 | 0.074 ± 0.001 |
| `empty` — B/op | 208 | 1 235 | 536 |
| `simple` — µs/op | 0.099 ± 0.001 | 0.988 ± 0.012 | 1.344 ± 0.051 |
| `simple` — B/op | 464 | 5 632 | 8 707 |
| `repeated` — µs/op | 0.135 ± 0.005 | 1.000 ± 0.021 | 4.738 ± 0.056 |
| `repeated` — B/op | 656 | 5 490 | 25 371 |
| `realistic` — µs/op | 0.615 ± 0.003 | 9.989 ± 0.082 | 59.398 ± 1.823 |
| `realistic` — B/op | 2 128 | 34 448 | 371 972 |

`realistic` is a docker-style CLI modeled on a subset of `docker` `run`/`pull`/`ps`
(github.com/docker/cli, Apache-2.0): one subcommand level, a 20-field `run` command with
mostly optional and four repeatable options, parsed from a 34-token command line — each
library in its idiomatic subcommand encoding (flagged an enum `Parser.CommandGroup`, mainargs
`ParserForMethods`, case-app options classes with first-token dispatch; definitions in
`bench-portable/src/defs/RealisticDefs.scala`, setup asserts all three agree on every parsed
field). It is the widest gap in the suite: flagged parses it 16× faster than mainargs and 97×
faster than case-app, allocating 16× and 175× less.

### flagged × mainargs (short clusters, typed leftover, `Map[K,V]`)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.109 ± 0.006 | 1.168 ± 0.043 |
| `bundled` — B/op | 504 | 7 072 |
| `leftover` — µs/op | 0.132 ± 0.001 | 0.295 ± 0.002 |
| `leftover` — B/op | 712 | 2 928 |
| `map` — µs/op | 0.288 ± 0.011 | 0.521 ± 0.008 |
| `map` — B/op | 1 008 | 3 742 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.077 ± 0.001 | 2.120 ± 0.031 |
| `counter` — B/op | 440 | 12 368 |
| `group` — µs/op | 0.157 ± 0.009 | 3.216 ± 0.025 |
| `group` — B/op | 624 | 18 510 |

On non-trivial argument lists flagged parses in 0.08–0.62 µs across all scenarios, 1.8–97×
faster than mainargs and case-app on the same inputs, allocating 3.7–175× less — the remaining
bytes are the parse's actual output (the config object, `Some` wrappers for declared Option
fields, value substrings of `=`-forms and `k=v` entries) plus one set of per-parse state
arrays. The closest contests are mainargs' `Map` (1.8×) and `Leftover` (2.2×), whose per-token
work is already minimal.

### Method-based commands (flagged × mainargs)

`bench.MethodBench` measures the method-parser path — the two libraries where commands are
annotated methods and a successful parse *invokes* one. `method` is a lone command method with
the `simple` grammar (`--foo hello --bar 42 --baz`); `commands` dispatches on the first token of
`add core --url https://x.git` across three methods. Setup asserts both libraries return the
same invoked result.

| Scenario | flagged | mainargs |
|---|---|---|
| `method` — µs/op | 0.099 ± 0.001 | 1.006 ± 0.040 |
| `method` — B/op | 432 | 5 800 |
| `commands` — µs/op | 0.097 ± 0.001 | 0.468 ± 0.004 |
| `commands` — B/op | 720 | 3 531 |

flagged parses-and-invokes 4.8–10× faster than mainargs, allocating 5–13× less. Method
commands cost flagged the same as its class derivation — `method` lands on the class-based
`simple` scenario's 0.099 µs exactly, the invoker being a direct call with one cast per
argument where class derivation constructs a case class. mainargs' `commands` run is *faster*
than its lone-method run because the chosen `add` command parses two parameters instead of
four.

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
| `empty` — µs/op | 0.034 | 0.003 | 0.003 | |
| `empty` — B/op | 208 | 48 | 48 | |
| `simple` — µs/op | 0.099 | 0.014 | 0.039 | |
| `simple` — B/op | 464 | 144 | 48 | |
| `repeated` — µs/op | 0.135 | 0.119 | 0.079 | |
| `repeated` — B/op | 656 | 560 | 144 | |
| `bundled` — µs/op | 0.109 | unsupported | 0.025 | |
| `bundled` — B/op | 504 | unsupported | 96 | |
| `wide25` — µs/op | 0.551 | 1.436 | | |
| `wide25` — B/op | 656 | 2 928 | | |
| `positional` — µs/op | 0.075 | | | 0.005 |
| `positional` — B/op | 472 | | | 32 |
| `positional25` — µs/op | 0.254 | | | 0.017 |
| `positional25` — B/op | 496 | | | 112 |

Hand-written parsers assign straight into locals or a small accumulator of the known types, so
they carry none of the generic machinery — no per-parse state arrays sized by the command's
arity, no erased value slots, no `Mirror`-based construction, no dispatch through parser
instances, and no help/suggestion/subcommand plumbing reachable from the hot path. That
machinery costs flagged ~56–84 ns and ~410–510 B per parse over the feature-parity baseline
(1.7–4.4×). Against the typical parser the gap is 7× on `simple`, nearly closes on `repeated`
(1.1× — the accumulator `copy` per token and `:+` append cost about what the whole engine
does), and inverts at `wide25`: the match chain tests up to 25 exact strings per token and
copies a 25-field accumulator per option, ending up 2.6× slower and 4.5× more allocating than
the engine's per-token hash lookup and value slots — and the idiom's cost grows with
options × tokens where the engine's grows with tokens.

`@main` remains ~15× faster on the positional grammars — sequential typed reads with no
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
| empty — flagged | 40 | 290 | 161 | 154 | 102 |
| empty — mainargs | 249 | 1 343 | 1 056 | 659 | 466 |
| empty — case-app | 139 | 373 | 257 | 262 | 173 |
| simple — flagged | 202 | 993 | 780 | 408 | 279 |
| simple — mainargs | 1 296 | 7 182 | 6 567 | 3 516 | 2 305 |
| simple — case-app | 1 431 | 4 647 | 3 367 | 6 098 | 3 151 |
| repeated — flagged | 148 | 1 075 | 953 | 522 | 354 |
| repeated — mainargs | 1 044 | 7 323 | 6 585 | 3 500 | 2 458 |
| repeated — case-app | 5 222 | 11 978 | 9 601 | 18 548 | 9 377 |
| bundled — flagged | 100 | 830 | 511 | 325 | 241 |
| bundled — mainargs | 1 211 | 8 065 | 6 416 | 3 893 | 2 725 |
| counter — flagged | 89 | 817 | 590 | 351 | 234 |
| counter — case-app | 2 225 | 6 456 | 4 171 | 8 362 | 4 560 |
| group — flagged | 174 | 1 319 | 967 | 550 | 386 |
| group — case-app | 3 602 | 9 572 | 6 355 | 12 789 | 6 966 |
| leftover — flagged | 161 | 1 423 | 717 | 529 | 406 |
| leftover — mainargs | 405 | 2 761 | 1 594 | 1 287 | 1 131 |
| realistic — flagged | 795 | 5 166 | 4 484 | 1 856 | 1 362 |
| realistic — mainargs | 9 758 | 61 471 | 42 649 | 24 728 | 16 591 |
| realistic — case-app | 72 642 | 198 645 | 127 283 | 257 346 | 152 352 |

flagged is the fastest of the three on every scenario on every platform. In the maxed Native
build flagged parses the small scenarios in 0.23–0.41 µs and the realistic docker-style line
in 1.4 µs — roughly 1.5–2.5× the portable-harness JVM column and ahead of Scala.js by 3–4×.
The WebAssembly backend beats the JavaScript one on every flagged scenario. case-app's
`realistic` parse is slower on Native than on the JVM or JS (0.15–0.26 ms per parse on every
platform).

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
