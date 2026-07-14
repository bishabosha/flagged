# claw

Scala 3-first command-line argument parsing. Define your CLI as plain data — a case
class for options, an enum for subcommands — add `derives Parser`, and parse with a
single call. No builder DSLs, no runtime reflection: parsers are derived at compile
time, and misconfigurations are compile errors.

## Features

- UNIX-idiomatic parsing: long names and `=` values, short aliases with attached
  values, flag bundling, `--` to end option parsing.
- Hierarchical subcommands derived from enums, nested arbitrarily deep, with `--help`
  generated at every level.
- Sensible defaults from the data model: kebab-cased long names, booleans as flags,
  `Option` fields optional, collections repeatable, field defaults respected —
  adjustable with a handful of annotations.
- Extensible value parsing via the `Reader` typeclass, with readers included for
  common types and derivable for simple enums.
- Helpful errors: did-you-mean suggestions, per-level help hints, conventional exit
  codes.
- Usable from `@main` methods, scala-cli scripts, or a plain `main`.

## Getting started

Build and test with scala-cli: `scala-cli test .`

Runnable demos live in `examples/`, and the suites under `test/claw/` document the
full behavior.
