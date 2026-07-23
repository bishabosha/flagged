# Benchmarks

Results from the last full run are in [`results.md`](results.md).

JMH benchmarks comparing flagged with [mainargs](https://github.com/com-lihaoyi/mainargs) and
[case-app](https://github.com/alexarchambault/case-app), on both derivation compile time and
parse latency/allocation. These modules are compiled but not run in CI.

## Running

JMH arguments go after the `bench.runJmh` task.

JMH covers the JVM; `bench-portable/` runs the same runtime scenarios (shared definitions in
`bench-portable/src/defs`) on Scala.js, Scala.js-on-Wasm, and Scala Native with a calibrated
best-of-rounds timer:

```console
$ ./mill bench-portable.jvm.run        # JVM reference
$ ./mill bench-portable.js.run         # Node
$ ./mill bench-portable.jsWasm.run     # Wasm
$ ./mill bench-portable.native.run     # Native (release-fast)
$ ./mill bench-portable.nativeMax.run  # Native (release-full, thin LTO, no GC)
```

```console
$ ./mill bench.runJmh                          # everything (slow)
$ ./mill bench.runJmh 'RuntimeBench.*'         # parse latency only
$ ./mill bench.runJmh 'CompileBench.*'         # compile time only
$ ./mill bench.runJmh -prof gc 'RuntimeBench.*'   # + allocation per parse
$ ./mill bench.runJmh -p lib=flagged -p scenario=options25 'CompileBench.*'
```

For allocation, read the `gc.alloc.rate.norm` rows (bytes allocated per parse). For quick
iteration add `-f 0 -wi 1 -i 1`; published numbers use the annotation defaults for
`CompileBench` (one forked JVM) and `-f 5 -prof gc` for the runtime suites (`RuntimeBench`,
`MethodBench`, `BaselineBench`) — five forks per benchmark for every library, latency and
allocation from the same runs.

## What is measured

**`CompileBench`** — wall time for a `dotty.tools.dotc.Driver` (sharing this process's classpath)
to compile one generated source file whose cost is dominated by parser derivation. The same CLI
surface is written idiomatically per library; `lib=baseline` compiles the equivalent declarations
with no parsing library at all, giving the compiler's floor for the file shape.

| scenario | shape |
|---|---|
| `options10` | one options class with ten mixed fields (strings, numerics, flags, a list, an `Option`) |
| `options25` | one options class with twenty-five defaulted fields (wide derivation) |
| `commands` | a three-command interface, each command with its own options |
| `methods` | the same interface as command *methods* (flagged `@run` + `Parser.methods`, mainargs `ParserForMethods`); mainargs' `commands` entry is already its method encoding, so its two rows measure the same source, and case-app has no method-based API, so its entry reuses the command-objects encoding |
| `realistic` | a docker-style CLI modeled on a subset of `docker` `run`/`pull`/`ps` (github.com/docker/cli, Apache-2.0): one subcommand level, a 20-field command with mostly optional and several repeatable options — each library in its idiomatic subcommand encoding (see `bench-portable/src/defs/RealisticDefs.scala`) |

**`RuntimeBench`** — average time per parse of a fixed command line, against parser instances
built once in setup (all three libraries pay derivation at compile time or construction once; the
benchmark isolates parsing). Setup asserts every scenario parses successfully, so a failing parse
cannot pose as a fast one.

| scenario | libraries | command line |
|---|---|---|
| `empty` | all three | `` (defaults only) |
| `simple` | all three | `--foo hello --bar 42 --baz` |
| `repeated` | all three | `--qux a --qux b --qux c --qux d` |
| `bundled` | flagged, mainargs | `-bfhello --bar 7` (case-app has no short clusters or attached values) |
| `leftover` | flagged, mainargs | `-s 2 1 2 3 4 5` — typed leftover `Int`s (case-app's remaining args are untyped; option first because mainargs treats everything after the first leftover token as leftover) |
| `counter` | flagged, case-app | `-v -v -v --target x` (`Count` vs `Int @@ Counter`; mainargs has no counters) |
| `group` | flagged, case-app | `--host h --port 8080 -q --log-level warn` — shared options group (splice vs `@Recurse`) |
| `realistic` | all three + scopt, scallop, picocli | a 34-token `run ...` command line against the docker-style CLI above (subcommand dispatch, 17 option occurrences of which 7 hit repeatable options, image + command positionals); setup asserts every library agrees on every parsed field. The scopt/scallop/picocli rows (`bench/src/RealisticJvmDefs.scala`, `PicocliDocker.java`) are runtime-only: builder/DSL/reflection libraries with no derivation to measure at compile time, and picocli is Java, so none appear in the compile or portable tables |

Definitions per library live in `src/defs/`; scenarios use the closest idiomatic encoding for
each (e.g. `Flag` for mainargs booleans, `@ExtraName` for case-app short names).

**`MethodBench`** — the method-based counterpart to `RuntimeBench`: parse-and-invoke latency for
command methods, flagged's `@run` derivation against mainargs' `ParserForMethods` (the two
libraries with a method parser). Both sides select a method, parse its parameters, and invoke
it; setup asserts both succeed and agree on the invoked result.

| scenario | command line |
|---|---|
| `method` | `--foo hello --bar 42 --baz` against a lone command method (the `simple` grammar) |
| `commands` | `add core --url https://x.git` against a three-command interface, dispatching on the first token |

## Caveats

- Compile times measure a warm in-process compiler on this module's classpath; absolute values
  are not comparable to cold `scala-cli`/sbt runs, only across `lib` values within a scenario.
- All three libraries sit on the benchmark classpath as jars: flagged as its packaged jar
  (`FlaggedFromJar` in `build.mill` swaps the local classes directory for `flagged.jvm.jar`),
  mainargs and case-app from the coursier cache — matching how a project resolving them from a
  Maven repository sees them, and keeping the compiler's classpath reads symmetric.
- Library versions are pinned in `build.mill`; results are only meaningful for the versions
  they were run against.
- flagged reports errors accumulated and case-app also continues after errors, while mainargs
  stops earlier; only successful parses are benchmarked, where the strategies do comparable work.
