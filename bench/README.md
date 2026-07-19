# Benchmarks

Results from the last full run are in [`results.md`](results.md).

JMH benchmarks comparing flagged with [mainargs](https://github.com/com-lihaoyi/mainargs) and
[case-app](https://github.com/alexarchambault/case-app), on both derivation compile time and
parse latency/allocation. This module is excluded from the main build (`//> using exclude` in the
root `project.scala`) and is not run in CI.

## Running

Requires scala-cli power mode; JMH arguments go after `--`.

```console
$ scala-cli --power run --jmh bench                          # everything (slow)
$ scala-cli --power run --jmh bench -- 'RuntimeBench.*'      # parse latency only
$ scala-cli --power run --jmh bench -- 'CompileBench.*'      # compile time only
$ scala-cli --power run --jmh bench -- -prof gc 'RuntimeBench.*'   # + allocation per parse
$ scala-cli --power run --jmh bench -- -p lib=flagged -p scenario=options25 'CompileBench.*'
```

For allocation, read the `gc.alloc.rate.norm` rows (bytes allocated per parse). For quick
iteration add `-f 0 -wi 1 -i 1`; published numbers should use the defaults (forked JVM, 5+5
iterations).

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

Definitions per library live in `src/defs/`; scenarios use the closest idiomatic encoding for
each (e.g. `Flag` for mainargs booleans, `@ExtraName` for case-app short names).

## Caveats

- Compile times measure a warm in-process compiler on this module's classpath; absolute values
  are not comparable to cold `scala-cli`/sbt runs, only across `lib` values within a scenario.
- Library versions are pinned in `project.scala`; results are only meaningful for the versions
  they were run against.
- flagged reports errors accumulated and case-app also continues after errors, while mainargs
  stops earlier; only successful parses are benchmarked, where the strategies do comparable work.
