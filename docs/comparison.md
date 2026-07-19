# Comparison with mainargs and case-app

Feature comparison against the two most-used Scala CLI parsing libraries, based on their current
documentation and test suites (mainargs 0.7.7–0.7.8 readme and `mainargs/test`; case-app 2.x docs at
alexarchambault.github.io/case-app and `tests/`). Behaviors that overlap are pinned down in
`test/flagged/ParitySuite.test.scala`; each test names the library whose documented behavior it was
checked against.

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
| Repetition of scalar options | last wins | error unless `allowRepeats` | error unless `Last[T]` |
| Collections | `List`, `Vector`, `Seq`, any via `Parser.repeated` | `Seq`, any `Iterable`, `Map[K,V]` | `List`, `Vector` only |
| `Map[K,V]` (`k=v`) | no | yes | no |
| `Option[T]` | yes | yes | yes |
| Defaults from field defaults | yes (lazy) | yes (lazy) | yes (lazy) |
| Positional arguments | `@positional`, ordering checked | `positional = true` or `allowPositional` | positionals are the remaining args |
| Variadic positionals | repeated `@positional` field | `Leftover[T]` / `T*` | `RemainingArgs` (untyped) |
| Typed leftover elements | yes | yes | no (strings) |
| Subcommands | enums, arbitrarily nested | one level (multiple `@main`s) | `CommandsEntryPoint`, nested via multi-word names |
| Option groups (splicing/`@Recurse`) | yes, nested | yes (`TokensReader.Class`), nested | yes (`@Recurse`), nested |
| Optional group (`Option[Group]`) | no (rejected statically) | no | yes |
| Prefixed group names | no | no | yes (`@Recurse("prefix")`) |
| `--help` | any position, per level | first token only, top level | any position, per command |
| Help: defaults / required / repeatable markers | yes / yes / yes | no / no / no | no / no / `*` |
| Did-you-mean suggestions | commands and options | no | no |
| Error aggregation | missing arguments only | missing + unknown + duplicate together | all error kinds together |
| Duplicate name detection | compile time (constant names) + construction | none documented | opt-in runtime check (`ensureNoDuplicates`) |
| Shape/annotation misuse | compile-time errors | some compile, most runtime | runtime |
| Hidden options / commands | no | `hidden = true` (options) | `@Hidden`, hidden commands, `--full-help` |
| Help sections/groups | no | no | `@Group`, sorted groups |
| App name/version in help | no | no | `@AppName`, `@AppVersion` |
| Multiple names per option | one long + one short | one long + one short | any number (`@Name` stacks) |
| Command aliases / default command | no / no | no / no | yes / yes |
| Unrecognized-argument passthrough | no | `Leftover` absorbs | `stopAtFirstUnrecognized`, `ignoreUnrecognized` |
| Shell completions | no | no | bash and zsh, `completions install` |
| Custom value types | `Parser.of` + `emap`/`map`; shape-preserving combinators | `TokensReader.Simple` | `SimpleArgParser.from` |
| Cross-field validation | `emap` on the command parser | manual, after parse | manual, after parse |
| Argument index tracking | no | no | `Indexed[T]` |
| Scala versions | 3 only | 2.12, 2.13, 3 | 2.12, 2.13, 3 |
| Platforms | JVM | JVM, JS, Native | JVM, JS, Native |

## Deliberate differences (kept, covered by ParitySuite)

- **Last-wins for repeated scalars.** Both libraries error on repetition by default. Last-wins
  matches common Unix tooling and makes shell aliases composable (`alias ll='ls -l'` style
  overriding); accumulation is opt-in through collection types.
- **Only kebab-case names match.** mainargs also accepts the raw `camelCase` spelling; accepting
  both doubles the effective namespace and hides collisions. Explicit `@name` values are matched
  verbatim in all three libraries.
- **No automatic short option for single-letter fields.** mainargs and case-app turn a field `v`
  into `-v` (short-only). flagged requires `@short('v')`; a single-letter field is `--v`.
- **`--` is an end-of-options marker** (POSIX guideline 10), optionally captured by a `Trailing`
  field. mainargs treats all-dash tokens as plain values; case-app agrees with flagged here.
- **`--help` works at every level and position.** mainargs only recognizes it as the first token.
- **Bare `Option[Boolean]` requires a value.** case-app interprets bare `--flag` as `Some(true)`;
  see follow-up 15 before committing either way.

## Gaps in flagged

Found in at least one of the two libraries and absent here — the follow-up list below ranks them.
Hidden options, help groups/sections, app version metadata, `Map[K,V]` values, multiple option
aliases, command aliases, default commands, optional and prefixed option groups, error
accumulation beyond missing arguments, unrecognized-argument passthrough, shell completions,
argument index tracking, Scala.js/Native, Scala 2.

One limitation surfaced by the parity tests: derivation nests one inline level per field, so
commands with roughly more than 20 fields exceed the compiler's default `-Xmax-inlines` (the test
scope raises it; mainargs handles 22+ fields out of the box).

## Features neither library has

For context, not gaps: hierarchical subcommands derived from nested enums, per-level help with
suggestions, compile-time duplicate/shape/ordering checks, typed `Trailing`, shape-preserving
`map`/`emap` on every parser (including whole commands, giving cross-field validation before the
run function), pluggable flag/repeated shapes.

## Follow-ups, ranked

1. **Accumulate all parse errors.** Report unknown options, invalid values, and missing arguments
   together instead of failing at the first non-missing error. Both libraries aggregate more than
   flagged does today; this is the most common friction in day-to-day use.
2. **Fix derivation depth for wide commands.** Split the field-tuple recursion (halving instead of
   head/tail) so ~20+ field commands compile without `-Xmax-inlines`. Remove the test-scope flag
   once done.
3. **Hidden options and commands.** `@hidden` annotation, omitted from help but parseable, plus a
   way to show them (case-app's `--full-help`). Needed by any CLI with deprecated or internal
   flags.
4. **App metadata.** Program version (and optional display name) in help, plus a `--version` flag.
5. **Scala.js and Scala Native cross-build.** Both competitors support all three platforms; CLI
   tools built with scala-cli increasingly target Native.
6. **Help sections.** A `@group("...")` annotation rendering options under titled sections, as in
   case-app. Matters once a command has more than a dozen options.
7. **Multiple aliases.** Repeatable `@name` on fields (case-app stacks `@Name`) and alias names for
   subcommand cases.
8. **Optional option groups.** Support `Option[Group]` for spliced commands: `None` unless any of
   the group's options is present (case-app `@Recurse` on `Option[T]`). Currently rejected at
   compile time.
9. **Prefixed option groups.** A prefix on a spliced group's option names, letting one group be
   spliced twice (case-app `@Recurse("prefix")`).
10. **`Map[K,V]` options.** `--define k=v` accumulation as in mainargs.
11. **Default command.** A `CommandGroup` case selected when no command token is given (case-app
    `defaultCommand`).
12. **Shell completions.** bash/zsh completion generation from the command model; the static model
    makes this mechanical, but it is a sizable feature (case-app ships it).
13. **Passthrough modes.** Stop-at-first-unrecognized and ignore-unrecognized parsing for wrapper
    CLIs that forward arguments (case-app has both; `Trailing` covers only the explicit `--` case).
14. **Digit boundaries in kebab-casing.** mainargs maps `optFor29Name` to `opt-for-29-name`;
    flagged produces `opt-for29-name`. Decide, document, and if changed, do it before any public
    release.
15. **Bare `Option[Boolean]` semantics.** Decide whether presence without a value should mean
    `Some(true)` (case-app) or remain an error (current behavior, pinned in ParitySuite).
16. **Low priority.** `--usage` (condensed help), alphabetical help sorting toggle, pluggable name
    mapper (snake_case), argument index tracking (`Indexed`), runtime name/doc overrides, Scala 2
    support (not planned: the design depends on Scala 3 inline derivation).
