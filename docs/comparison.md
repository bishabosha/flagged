# Comparison with mainargs and case-app

Feature comparison against the two most-used Scala CLI parsing libraries, based on their current
documentation and test suites (mainargs 0.7.7–0.7.8 readme and `mainargs/test`; case-app 2.x docs at
alexarchambault.github.io/case-app and `tests/`). Behaviors that overlap are pinned down in
`test/flagged/ParitySuite.test.scala`; each test names the library whose documented behavior it was
checked against. JMH benchmarks comparing derivation compile time and parse latency/allocation
across the three libraries live in `bench/` (see `bench/README.md`).

## Feature matrix

| Feature | flagged | mainargs | case-app |
| --- | --- | --- | --- |
| Definition style | case class / enum `derives` | `@main` methods or case class | case class |
| Derivation mechanism | `Mirror` + inline (one macro pair for annotations/defaults) | macros | shapeless (Scala 2) / macros (Scala 3) |
| `--name value` / `--name=value` | yes / yes | yes / yes | yes / yes |
| Short options `-s value`, `-svalue`, `-s=value` | yes | yes | no attached form |
| Short-flag bundling `-abc`, `-ovalue` at end | yes | yes | no |
| `--` end of options | yes (plus typed `Trailing`) | no (`--` is a plain value) | yes (raw `unparsed`) |
| Flags | `Boolean` (accepts `=value`), `Count`, custom via `Parser.flag` | `Flag` (no `=value`) | `Boolean` (`=value` only), `Int @@ Counter` |
| Counting flags | `Count` | no | `Int @@ Counter`, `List[Unit]` |
| Repetition of scalar options | last wins (values and flags; `Count` accumulates) | error unless `allowRepeats` | error unless `Last[T]` |
| Collections | any with a `Factory` (`List`, `Set`, ..., `Map[K,V]`), any type via `Parser.repeated` | `Seq`, any `Iterable`, `Map[K,V]` | `List`, `Vector` only |
| `Map[K,V]` (`k=v`) | yes | yes | no |
| `Option[T]` | yes | yes | yes |
| Defaults from field defaults | yes (lazy) | yes (lazy) | yes (lazy) |
| Positional arguments | `@positional`, ordering checked | `positional = true` or `allowPositional` | positionals are the remaining args |
| Variadic positionals | repeated `@positional` field | `Leftover[T]` / `T*` | `RemainingArgs` (untyped) |
| Typed leftover elements | yes | yes | no (strings) |
| Subcommands | enums, arbitrarily nested | one level (multiple `@main`s) | `CommandsEntryPoint`, nested via multi-word names |
| Option groups (splicing/`@Recurse`) | yes, nested | yes (`TokensReader.Class`), nested | yes (`@Recurse`), nested |
| Optional group (`Option[Group]`) | yes | no | yes |
| Prefixed group names | yes (`@name` on the group field) | no | yes (`@Recurse("prefix")`) |
| `--help` | any position, per level | first token only, top level | any position, per command |
| Help: defaults / required / repeatable markers | yes / yes / yes | no / no / no | no / no / `*` |
| Did-you-mean suggestions | commands and options | no | no |
| Error aggregation | all error kinds together | missing + unknown + duplicate together | all error kinds together |
| Duplicate name detection | compile time (constant names) + construction | none documented | opt-in runtime check (`ensureNoDuplicates`) |
| Shape/annotation misuse | compile-time errors | some compile, most runtime | runtime |
| Hidden options / commands | `@hidden` (options and commands), revealed by `--help-all` | `hidden = true` (options) | `@Hidden`, hidden commands, `--full-help` |
| Help sections/groups | `@group` (also on spliced groups) | no | `@Group`, sorted groups |
| App name/version in help | `@version` + a `Versioned` instance, `--version` (`@name` sets the prog name) | no | `@AppName`, `@AppVersion` |
| Multiple names per option | repeatable `@name` + one short | one long + one short | any number (`@Name` stacks) |
| Command aliases / default command | repeatable `@name` / `@default` (with arg forwarding) | no / no | yes / yes |
| Unrecognized-argument passthrough | no | `Leftover` absorbs | `stopAtFirstUnrecognized`, `ignoreUnrecognized` |
| Shell completions | no | no | bash and zsh, `completions install` |
| Custom value types | `Parser.of` + `emap`/`map`; shape-preserving combinators | `TokensReader.Simple` | `SimpleArgParser.from` |
| Cross-field validation | `emap` on the command parser | manual, after parse | manual, after parse |
| Argument index tracking | no | no | `Indexed[T]` |
| Scala versions | 3 only | 2.12, 2.13, 3 | 2.12, 2.13, 3 |
| Platforms | JVM, JS, Native (`java.time`/`Path` instances per platform) | JVM, JS, Native | JVM, JS, Native |

## Deliberate differences (covered by ParitySuite)

- **Last-wins for repeated options, flags included.** Both libraries error on repetition by
  default. Last-wins matches common Unix tooling and makes shell aliases composable
  (`alias ll='ls -l'` style overriding): a repeated boolean flag replaces the previous value,
  so `--verbose=false --verbose` is true. Accumulation is opt-in through collection types and
  `Count`.
- **Only kebab-case names match.** mainargs also accepts the raw `camelCase` spelling; accepting
  both doubles the effective namespace and hides collisions. Explicit `@name` values are matched
  verbatim in all three libraries.
- **No automatic short option for single-letter fields.** mainargs and case-app turn a field `v`
  into `-v` (short-only). flagged requires `@short('v')`; a single-letter field is `--v`.
- **`--` is an end-of-options marker** (POSIX guideline 10), optionally captured by a `Trailing`
  field. mainargs treats all-dash tokens as plain values; case-app agrees with flagged here.
- **`--help` works at every level and position.** mainargs only recognizes it as the first token.

## Features only flagged has

For context: hierarchical subcommands derived from nested enums, per-level help with
suggestions, compile-time duplicate/shape/ordering checks, typed `Trailing`, shape-preserving
`map`/`emap` on every parser (including whole commands, giving cross-field validation before the
run function), pluggable flag/repeated shapes.

## Gaps in flagged, ranked

1. **Shell completions.** bash/zsh completion generation from the command model; the static model
   makes this mechanical, but it is a sizable feature (case-app ships it).
2. **Passthrough modes.** Stop-at-first-unrecognized and ignore-unrecognized parsing for wrapper
   CLIs that forward arguments (case-app has both; `Trailing` covers the explicit `--` case, and
   `@default`-command forwarding covers the leading-command case). Needs an API-surface decision:
   a parse-time flag does not fit the typed model as well as a `Passthrough`-shaped field would.
3. **Low priority.** `--usage` (condensed help), alphabetical help sorting toggle, pluggable name
   mapper (snake_case), argument index tracking (`Indexed`), runtime name/doc overrides, Scala 2
   support (not planned: the design depends on Scala 3 inline derivation).
