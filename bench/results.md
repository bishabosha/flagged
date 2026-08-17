# Benchmark results

Produced by the suites in this directory (see `README.md` for methodology and caveats). All
tables were measured in one session at the commit below — the state where derivation *is*
assembly (the inline field walk feeds `Assemble.FieldsBuilder` directly and a constructed
command is parse-ready, with its lookup tables built and nothing deferred) and the engine
builds commands into destination slots: per-level parse frames, compacted per-parse state
(seen-bit words plus lazily allocated side arrays), and defaults and `@cmd` execution deferred
until the whole selected command chain has validated — with the two-annotation surface
(`@cmd`/`@opt`) sparsely mirrored: only the arguments written at a use site are encoded
(`flagged.meta.ArgumentList`), so an argument left at its default costs nothing at derivation
— on top of the multi-token features (`Parser.Product`, `@opt(split)`, `@opt(greedy)`), the
`Parser.Shared` splice model, and the single-pass compile-time rules layer (see
`bench.RuleCostProbe` for the measurements behind that encoding) — with flagged on the
benchmark classpath as the packaged jar of its `stable` twin (`FlaggedFromJar` in
`build.mill`): the capture-checked sources mechanically rewritten by the `Uncheck` plugin and
compiled with no experimental feature — exactly the published artifact, like the
mainargs/case-app jars from the coursier cache. JMH scores are averages ± 99.9% confidence
intervals; the compile table is one forked JVM (3 warmup + 5 measurement iterations), and the
runtime tables (`RuntimeBench`, `ConstructBench`, `OneshotBench`, `MethodBench`,
`BaselineBench`) are five forked JVMs per benchmark (5 warmup + 5 measurement iterations
each, all libraries alike), with allocation from the same `-prof gc` runs.

- Date: 2026-08-17, flagged commit `93e1ac8`
- Hardware: Apple M3 Max, 64 GB, macOS 26.5.1
- JVM: Azul Zulu OpenJDK 25.0.2, Scala 3.9.0-RC4, JMH 1.37
- Library versions: mainargs 0.7.8, case-app 2.1.0; `realistic` runtime rows also scopt 4.1.0,
  scallop 5.1.0, picocli 4.7.6

## Compile time

Milliseconds to compile one generated source file with a warm in-process `dotty` driver.
`baseline` is the same declarations with no parsing library — the compiler's floor for the file
shape. Only comparisons within a row are meaningful.

| Scenario | baseline | flagged | mainargs | case-app |
|---|---|---|---|---|
| `options10` (10 mixed fields) | 53.6 ± 4.8 | 100.9 ± 8.6 | 241.1 ± 25.1 | 460.7 ± 34.4 |
| `options25` (25 defaulted fields) | 47.3 ± 2.4 | 123.2 ± 16.3 | 246.3 ± 19.0 | 502.5 ± 42.5 |
| `commands` (3 subcommands) | 48.6 ± 4.5 | 108.6 ± 7.1 | 264.9 ± 21.5 | 467.2 ± 65.3 |
| `methods` (3 command methods) | 29.6 ± 1.4 | 113.6 ± 9.7 | 254.5 ± 43.4 | 497.3 ± 144.2 |
| `realistic` (docker-style CLI) | 58.1 ± 5.8 | 164.3 ± 10.5 | 213.0 ± 25.7 | 520.5 ± 68.6 |

Derivation cost over the baseline: flagged adds ~47–106 ms, mainargs ~155–225 ms, case-app
~407–468 ms; flagged is the cheapest of the three in every scenario, including the wide
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
| 4 | 119 | 126 | 262 |
| 8 | 109 | 127 | 242 |
| 16 | 120 | 134 | 247 |
| 32 | 138 | 169 | 259 |
| 64 | 177 | 267 | 287 |
| 128 | 286 | 524 | 311 |

Marginal cost is roughly 1.5 ms per unannotated field (~3.5 ms with half the fields annotated)
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
| `simple` — µs | 0.139 ± 0.006 | 0.179 ± 0.003 | 0.139 ± 0.001 |
| `simple` — B | 1 464 | 1 600 | 1 808 |
| `realistic` — µs | 1.351 ± 0.019 | 1.347 ± 0.019 | 1.011 ± 0.007 |
| `realistic` — B | 11 333 | 9 512 | 12 168 |

Construction is the one table where flagged is not uniformly fastest: case-app constructs the
realistic CLI ~1.3× quicker, while flagged and mainargs are within each other's error there
(flagged builds the complete parse-ready model — lookup maps included — where the others
defer some model work into each parse; the parse tables below show where that deferral
lands). On `simple`, flagged ties case-app at the front while allocating the least. The
one-shot measurement adds the halves up honestly — each library constructs what its idiom
needs for the invocation: flagged and mainargs derive the whole command group, case-app's
first-token dispatch constructs only the invoked command's parser, scopt rebuilds its
`OParser` chain, scallop's `ScallopConf` is construct-and-parse by design, and picocli
rebuilds its reflective model:

| One-shot | µs/op | B/op |
|---|---|---|
| `simple` — flagged | 0.330 ± 0.012 | 1 872 |
| `simple` — mainargs | 1.158 ± 0.011 | 6 965 |
| `simple` — case-app | 1.527 ± 0.103 | 10 368 |
| `realistic` — flagged | 2.247 ± 0.016 | 13 720 |
| `realistic` — mainargs | 11.482 ± 0.203 | 41 614 |
| `realistic` — scopt | 49.027 ± 0.624 | 107 901 |
| `realistic` — case-app | 51.935 ± 2.442 | 369 246 |
| `realistic` — scallop | 141.698 ± 0.598 | 445 675 |
| `realistic` — picocli | 232.840 ± 2.293 | 396 725 |

End to end, flagged is the fastest and lightest on both grammars: 3.5–4.6× faster than
mainargs and case-app on `simple`, 5.1× faster than mainargs and 22–104× faster than the rest
on `realistic`, allocating 3.0–33× less. flagged's one-shot is ~40–60% construction; every
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
| `empty` — µs/op | 0.044 ± 0.001 | 0.134 ± 0.003 | 0.079 ± 0.001 |
| `empty` — B/op | 240 | 944 | 536 |
| `simple` — µs/op | 0.212 ± 0.001 | 0.992 ± 0.027 | 1.375 ± 0.071 |
| `simple` — B/op | 408 | 5 174 | 9 114 |
| `repeated` — µs/op | 0.250 ± 0.002 | 0.901 ± 0.049 | 3.896 ± 0.030 |
| `repeated` — B/op | 544 | 5 034 | 25 397 |

### flagged × mainargs (short clusters, typed leftover, `Map[K,V]`)

| Scenario | flagged | mainargs |
|---|---|---|
| `bundled` — µs/op | 0.199 ± 0.005 | 1.061 ± 0.045 |
| `bundled` — B/op | 448 | 6 438 |
| `leftover` — µs/op | 0.159 ± 0.002 | 0.274 ± 0.001 |
| `leftover` — B/op | 568 | 2 720 |
| `map` — µs/op | 0.316 ± 0.005 | 0.500 ± 0.003 |
| `map` — B/op | 800 | 3 661 |

### flagged × case-app (counters, option groups)

| Scenario | flagged | case-app |
|---|---|---|
| `counter` — µs/op | 0.195 ± 0.001 | 2.030 ± 0.018 |
| `counter` — B/op | 392 | 12 936 |
| `group` — µs/op | 0.247 ± 0.002 | 2.974 ± 0.062 |
| `group` — B/op | 464 | 18 406 |

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
| flagged | 0.752 ± 0.007 | 2 114 |
| mainargs | 8.999 ± 0.176 | 32 434 |
| scopt 4.1.0 | 54.982 ± 2.836 | 86 012 |
| case-app | 56.667 ± 1.714 | 389 364 |
| picocli 4.7.6 | 59.610 ± 0.970 | 86 904 |
| scallop 5.1.0 | 139.672 ± 0.828 | 444 448 |

flagged parses this line 12× faster than mainargs and 73–186× faster than the rest,
allocating 15–210× less. The non-derivation libraries pay for their models at parse time:
scopt copies its 30-field config on every action, picocli walks its reflective model (built
once in setup — `parseArgs` resets and repopulates the annotated fields), and scallop
constructs and verifies the whole `ScallopConf` per argument list, which is its usage model —
definition and parse are coupled, so parser construction is part of every parse.

On non-trivial argument lists flagged parses in 0.16–0.75 µs across all scenarios, 1.6–75×
faster than mainargs and case-app on the same inputs, allocating 4.6–184× less — the remaining
bytes are the parse's actual output (the config object, `Some` wrappers for declared Option
fields, value substrings of `=`-forms and `k=v` entries) plus one set of per-parse state
arrays. The closest contests are mainargs' `Map` (1.6×) and `Leftover` (1.7×), whose per-token
work is already minimal.

### Method-based commands (flagged × mainargs)

`bench.MethodBench` measures the method-parser path — the two libraries where commands are
annotated methods and a successful parse *invokes* one. `method` is a lone command method with
the `simple` grammar (`--foo hello --bar 42 --baz`); `commands` dispatches on the first token of
`add core --url https://x.git` across three methods. Setup asserts both libraries return the
same invoked result.

| Scenario | flagged | mainargs |
|---|---|---|
| `method` — µs/op | 0.221 ± 0.017 | 0.995 ± 0.034 |
| `method` — B/op | 376 | 5 349 |
| `commands` — µs/op | 0.101 ± 0.020 | 0.478 ± 0.017 |
| `commands` — B/op | 464 | 3 326 |

flagged parses-and-invokes 4.5–4.7× faster than mainargs, allocating 7.2–14× less. Method
commands cost flagged no more than its class derivation — `method` at 0.221 µs against the
class-based `simple` scenario's 0.212 µs on the same grammar, within the method run's error
interval — the invoker being a direct call with one cast per argument where class derivation
constructs a case class. mainargs' `commands` run is *faster* than its lone-method run because
the chosen `add` command parses two parameters instead of four.

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
| `empty` — µs/op | 0.044 | 0.003 | 0.003 | |
| `empty` — B/op | 240 | 48 | 48 | |
| `simple` — µs/op | 0.212 | 0.017 | 0.036 | |
| `simple` — B/op | 408 | 144 | 48 | |
| `repeated` — µs/op | 0.250 | 0.057 | 0.070 | |
| `repeated` — B/op | 544 | 490 | 144 | |
| `bundled` — µs/op | 0.199 | unsupported | 0.022 | |
| `bundled` — B/op | 448 | unsupported | 96 | |
| `wide25` — µs/op | 0.594 | 1.689 | | |
| `wide25` — B/op | 512 | 2 928 | | |
| `positional` — µs/op | 0.085 | | | 0.004 |
| `positional` — B/op | 336 | | | 32 |
| `positional25` — µs/op | 0.260 | | | 0.025 |
| `positional25` — B/op | 392 | | | 112 |

Hand-written parsers assign straight into locals or a small accumulator of the known types, so
they carry none of the generic machinery — no per-parse state sized by the command's arity, no
erased value slots, no `Mirror`-based construction, no dispatch through parser instances, and
no help/suggestion/subcommand plumbing reachable from the hot path. That machinery costs
flagged ~40–180 ns and ~190–400 B per parse over the feature-parity baseline (3.6–15×).
Against the typical parser the gap is 12.5× on `simple`, narrows to 4.4× on `repeated` (the
accumulator `copy` per token and `:+` append cost nearly what the whole engine does), and
inverts at `wide25`: the match chain tests up to 25 exact strings per token and copies a
25-field accumulator per option, ending up 2.8× slower and 5.7× more allocating than the
engine's per-token hash lookup and value slots — and the idiom's cost grows with
options × tokens where the engine's grows with tokens.

`@main` remains ~10–21× faster on the positional grammars — sequential typed reads with no
option routing, no error accumulation, and no help — but positionals are all it can express,
and errors are thrown, not reported.

### Cross-platform (Scala.js, Wasm, Scala Native)

`bench-portable/` re-runs the same scenarios against the same parser definitions on the
platforms JMH cannot cover, with a calibrated best-of-5-rounds timer (~250 ms rounds). The JVM
column below uses the same portable harness for comparability — its closure indirection and
short calibrated rounds add overhead to every library, so the JMH tables above stay canonical
for JVM. Scala.js 1.22.0 on Node 26 (`js`, plus the `jsWasm` module for the WebAssembly
backend, linked at ES2022 as that backend requires), Scala Native 0.5.12 in `release-fast`;
the last column is the `nativeMax` module — the recommended Native release configuration
(release-full, thin LTO, no GC). The JVM, JS, and Native columns run the `stable` twin like
the JMH suites; `jsWasm` compiles the checked sources directly (the Wasm target has no stable
twin — the `Uncheck` rewrite is mechanical, so the measured code is the same).

ns per parse, best of 5 rounds:

| Benchmark | JVM | JS | JS/Wasm | Native | Native (max) |
|---|---|---|---|---|---|
| empty — flagged | 54 | 290 | 205 | 148 | 112 |
| empty — mainargs | 206 | 1 342 | 1 031 | 646 | 496 |
| empty — case-app | 131 | 386 | 262 | 257 | 181 |
| simple — flagged | 188 | 946 | 799 | 431 | 297 |
| simple — mainargs | 1 512 | 7 260 | 6 585 | 3 412 | 2 421 |
| simple — case-app | 1 346 | 4 442 | 3 456 | 6 004 | 3 327 |
| repeated — flagged | 216 | 1 308 | 941 | 537 | 383 |
| repeated — mainargs | 1 143 | 7 218 | 6 577 | 3 553 | 2 473 |
| repeated — case-app | 4 212 | 11 621 | 9 718 | 18 192 | 10 012 |
| bundled — flagged | 212 | 836 | 586 | 346 | 252 |
| bundled — mainargs | 1 363 | 8 024 | 6 342 | 3 908 | 2 991 |
| counter — flagged | 204 | 818 | 651 | 359 | 257 |
| counter — case-app | 1 906 | 6 134 | 4 295 | 8 291 | 4 803 |
| group — flagged | 244 | 1 183 | 958 | 502 | 354 |
| group — case-app | 3 063 | 9 087 | 6 408 | 12 458 | 7 270 |
| leftover — flagged | 193 | 1 215 | 760 | 537 | 406 |
| leftover — mainargs | 306 | 2 654 | 1 626 | 1 278 | 1 126 |
| realistic — flagged | 787 | 5 242 | 4 521 | 2 099 | 1 534 |
| realistic — mainargs | 9 627 | 59 289 | 42 588 | 23 858 | 16 663 |
| realistic — case-app | 59 706 | 191 403 | 127 586 | 255 537 | 149 515 |

flagged is the fastest of the three on every scenario on every platform. In the maxed Native
build flagged parses the small scenarios in 0.11–0.41 µs and the realistic docker-style line
in 1.5 µs — roughly 1.2–2.1× the portable-harness JVM column and ahead of Scala.js by
2.6–3.4×. The WebAssembly backend beats the JavaScript one on every flagged scenario.
case-app's `realistic` parse is slower on Native than on the JVM or JS (0.06–0.26 ms per
parse across the platforms).

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
directly: construct + parse in 0.33 µs (`simple`) / 2.2 µs (`realistic`), fastest of the six
libraries end to end. The compile table remains the practically dominant one — it is paid on
every build, not once per run.
