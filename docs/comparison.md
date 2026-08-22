# Comparison with other CLI parsing libraries

This document compares the features of Flagged to:
- mainargs (0.7.7–0.7.8)
- case-app (2.x)
- scopt (4.1.0)
- scallop (5.1.0)
- and picocli (4.7.6, the de-facto Java standard).

Comparisons to mainargs, case-app, scopt, scallop, and picocli entries are based on their documentation, but also concrete tests (the `realistic` scenario tests the same schema across all libraries). `flagged/test/src/flagged/ParitySuite.scala` also documents specific behavior.

JMH benchmarks comparing derivation compile time, runtime parser construction, the one-shot construct-and-parse cost, and parse latency/allocation live in `bench/` (see `bench/README.md`, current numbers in `bench/results.md`).

## The libraries

All the libraries support generation of help text, and mapping of command line arguments to values
according to a schema. They differ in how to construct the schema, when validation of the schema is performed, and how to declare various metadata needed for help-text.

| Library | Overview |
| --- | --- |
| flagged | Type class model, automatic derivation of option parser for case classes (`derives Parser.Command`), subcommand enums (`derives Parser.CommandGroup`), and `@cmd` annotated methods; one-call entry via `Flagged.parseOrExit`, or `Flagged.entry` to resolve the grammar once and parse many times. Compile-time validation of the schema, including duplicate names when they are constants (dynamic names are checked at parser construction) |
| mainargs | Type class model, automatic derivation of option parser for case class / `@main` annotated methods. Automatic assembly of subcommands (1-level, methods only) |
| case-app | Type class model, automatic derivation of option parser for case class. Mixin traits used to assemble subcommands, override features. |
| scopt | Type class model (for values only), options parser constructed with builder pattern. Arbitrary nesting of subcommands supported. Validation at runtime. |
| scallop | Type class model (for values only), options parser build via mutable DSL. Arbitrary nesting of subcommands supported. Validation at runtime. |
| picocli | annotated Java-first classes, runtime reflection and runtime validation. Custom converters declared via annotation and loaded with reflection. |

## Feature matrix

| Feature | Supported by Flagged? | And other libraries? |
| --- | --- | --- |
| `--name value` / `--name=value` | yes / yes | all (scopt also `--name:value`) |
| Short option values `-s value` / `-svalue` / `-s=value` | all three forms | mainargs, picocli: all three; scopt: no attached form; scallop: no `=` form; case-app: separate only |
| Short-flag bundling (`-abc`, `-ovalue` at end) | yes | mainargs, scallop, picocli; scopt: flags only |
| `--` end of options | yes, plus typed `Trailing` (absent vs present-but-empty) | all except mainargs (`--` is a plain value); case-app raw `unparsed` |
| Flags | `Boolean` (accepts `=value`), custom shapes via `Parser.flag` | mainargs `Flag` (no `=value`); case-app `Boolean` (`=value` only); scopt `opt[Unit]`; scallop `opt[Boolean]`; picocli `boolean` |
| Negatable flags (`--no-verbose`) | no — spelled `--verbose=false` | scallop `toggle`; picocli `negatable = true` |
| Counting flags (`-vvv`) | `Count` | case-app `Int @@ Counter`; scallop `tally()`; picocli `boolean[]` length |
| Repeated scalar options | last wins (values and flags) | error in all five (opt-outs: mainargs `allowRepeats`, case-app `Last[T]`, picocli `overwrittenOptionsAllowed`) |
| Repeatable → collections | any collection with a `Factory`; any type via `Parser.repeated` | mainargs `Seq`/`Iterable`/`Map`; case-app `List`/`Vector`; scallop `opt[List[T]]`; picocli arrays/collections/`Map`; scopt: one comma-separated token |
| Multi-value arity (`--point 1 2 3`) | fixed arity: tuple or `derives Parser.Product` fields; `1..*` via `@opt(greedy = true)` on repeated fields (compile error alongside positionals/subcommands — the ambiguity other libraries document away) | scallop multi-value options; picocli `arity = "2..3"` |
| Value splitting (`--env A,B,C` → elements) | `@opt(split = ...)` on repeated fields (separator char) | scopt (its native collection format); picocli `split` regex |
| `Map[K,V]` (`--x k=v`) | yes | mainargs; scopt (comma-separated pairs); scallop `props` (`-Dk=v`); picocli |
| `Option[T]` | yes | mainargs, case-app; scallop `ScallopOption[T]`; picocli (incl. `Optional<T>`); scopt: config fields, options optional by default |
| Defaults from field defaults | yes (lazy) | mainargs, case-app (lazy); scopt initial config instance; scallop `default =` parameter; picocli field initializers |
| Environment-variable defaults | via lazy field defaults (not shown in help) | picocli `${env:VAR}`, default-value providers |
| Positional arguments | unannotated fields (as `@scala.main`); `@opt(positional = true)` to combine with metadata; ordering checked | mainargs `positional = true`; case-app: the remaining args; scopt `arg[T]`; scallop `trailArg[T]`; picocli `@Parameters(index = ...)` |
| Variadic positionals, typed | repeated positional field | mainargs `Leftover[T]`; scopt `.unbounded()`; scallop `trailArg[List[T]]`; picocli `index = "1..*"`; case-app `RemainingArgs` (untyped) |
| Number-only options (`-5`, as `tail -5`) | no — `-<digits>` is a value, by design | scallop `number()` |
| Subcommands | enums or `@cmd` objects, arbitrarily nested | scallop, picocli: arbitrarily nested; case-app: nested via multi-word names; scopt `cmd().children`, nestable into one flat config; mainargs: one level |
| Shared option groups (splicing) | `derives Parser.Shared`, nested; splice-safety invariants checked at the group's derivation | mainargs `TokensReader.Class`; case-app `@Recurse`; picocli `@Mixin`; scopt: builder fragments (values stay flat) |
| Embedding a foreign command as a subcommand | sole-field group case substitutes the full command | picocli: subcommand classes from any source; case-app: `Command` objects |
| Optional group | `Option[Group]` | case-app; picocli `@ArgGroup` multiplicity `0..1` |
| Prefixed group names | `@opt(name = ...)` on the group field | case-app `@Recurse("prefix")` |
| Declarative cross-option constraints | no — `emap` checks after parse | scallop `conflicts`/`codependent`/`requireOne`; picocli `@ArgGroup` exclusive / co-occurring |
| `--help` | any position, per level | case-app, scallop, picocli: per command; mainargs: first token, top level only; scopt: one usage screen for the whole tree |
| Help markers: defaults / required / repeatable | yes / yes / yes | case-app: `*` for repeatable; picocli: opt-in templates (`${DEFAULT-VALUE}`, `requiredOptionMarker`) |
| Did-you-mean suggestions | commands and options | picocli ("possible solutions") |
| Error aggregation | all error kinds together | case-app: all kinds; mainargs: missing + unknown + duplicate; scopt, picocli: unknown options together, else first; scallop: first error |
| Duplicate name detection | compile time (constant names, `@cmd` command names included) + construction (dynamic names) | case-app: opt-in runtime (`ensureNoDuplicates`); scallop, picocli: at model build/`verify()`; mainargs: none documented |
| Hidden options / commands | `@opt(hidden = true)` / `@cmd(hidden = true)`, revealed by `--help-all` | mainargs, scallop, picocli `hidden = true`; case-app `@Hidden`, `--full-help`; scopt `.hidden()` |
| Help sections | `@opt(group = ...)` (also on spliced groups) | case-app `@Group`, sorted; scallop `group()`; picocli `@ArgGroup(heading = ...)` |
| Name & version in help | `@version("1.0")` literal, or bare `@version` via a `Versioned` instance; `--version` | case-app `@AppName`/`@AppVersion`; scopt `head(...)`/`version(...)`; scallop `version(...)`; picocli `@Command(version = ...)` |
| Multiple names per option | `name` plus `aliases` + one short | case-app, picocli: any number; scopt: long + short + `abbr("ab")`; mainargs: one long + one short |
| Command aliases / default command | `@cmd` aliases / `@cmd(default = true)` (with arg forwarding) | case-app: both; scallop: aliases; picocli: `aliases` / parent command runs |
| Unrecognized-argument passthrough | no | mainargs: `Leftover` absorbs; case-app `stopAtFirstUnrecognized`/`ignoreUnrecognized`; scopt `errorOnUnknownArgument = false`; picocli `unmatchedArgumentsAllowed`/`stopAtUnmatched`/`stopAtPositional` |
| Shell completions | no | case-app, picocli: bash and zsh |
| Custom value types | `Parser.of` + shape-preserving `map`/`emap` combinators | mainargs `TokensReader.Simple`; case-app `SimpleArgParser`; scopt `Read`; scallop `ValueConverter`; picocli `ITypeConverter` |
| Cross-field validation | `emap` on the command parser | scopt `checkConfig`; scallop `validate*` hooks; mainargs, case-app: manual after parse |
| Argument index tracking | no | case-app `Indexed[T]` |

## Deliberate differences (covered by ParitySuite)

- **Last-wins for repeated options, flags included.** Every other library here errors on
  repetition by default, making flagged the outlier by design. Last-wins matches common Unix
  tooling and makes shell aliases composable (`alias ll='ls -l'` style overriding): a repeated
  boolean flag replaces the previous value, so `--verbose=false --verbose` is true.
  Accumulation is opt-in through collection types and `Count`.
- **Only kebab-case names match.** mainargs also accepts the raw `camelCase` spelling; accepting
  both doubles the effective namespace and hides collisions. Explicit `name` values are matched
  verbatim in flagged, mainargs, and case-app alike.
- **No automatic short option for single-letter fields.** mainargs and case-app turn a field `v`
  into `-v` (short-only). flagged requires `@opt(short = 'v')`; a single-letter field is `--v`.
- **`--` is an end-of-options marker** (POSIX guideline 10), optionally captured by a `Trailing`
  field. mainargs treats all-dash tokens as plain values; the other four agree with flagged.
- **`--help` works at every level and position.** mainargs only recognizes it as the first token.

## Features only flagged has

Across all six libraries: the grammar validated at compile time (duplicate names, shape and
annotation misuse, positional ordering); shape-preserving `map`/`emap` on every parser —
including whole commands, giving cross-field validation before the run function — and
pluggable flag/repeated shapes; a typed `Trailing` field distinguishing an absent `--` from a
present-but-empty one; and command methods whose parse result is the invoked method's result,
typed as the union across the group (mainargs invokes methods but types the result as `Any`).
Hierarchical subcommands, per-level help, and did-you-mean suggestions — unique among the
derivation libraries — are matched in the wider field by scallop and picocli.

## Gaps in flagged, ranked

1. **Shell completions.** bash/zsh completion generation from the command model; the static model
   makes this mechanical, but it is a sizable feature (case-app and picocli both ship it).
2. **Passthrough modes.** Stop-at-first-unrecognized and ignore-unrecognized parsing for wrapper
   CLIs that forward arguments (case-app has both, picocli has `stopAtUnmatched`/
   `stopAtPositional`, scopt can ignore unknown arguments; `Trailing` covers the explicit `--`
   case, and default-command forwarding covers the leading-command case). Needs an API-surface
   decision: a parse-time flag does not fit the typed model as well as a `Passthrough`-shaped
   field would.
3. **Negatable flags.** An auto-generated `--no-verbose` complement (picocli `negatable`,
   scallop `toggle`). flagged spells the negative form `--verbose=false`; a flag-shape variant
   could add the `--no-` spelling and show it in help.
4. **Declarative cross-option constraints.** Mutually-exclusive and co-occurring option groups
   (picocli `ArgGroup`, scallop `conflicts`/`codependent`/`requireOne`). `emap` expresses the
   check but runs after parse — schema-level constraints could be reflected in usage output
   and completions.
5. **Low priority.** `--usage` (condensed help), alphabetical help sorting toggle, pluggable name
   mapper (snake_case), argument index tracking (`Indexed`), number-only options (scallop's
   `tail -5` style — flagged reserves `-<digits>` as values by design), abbreviated long-option
   matching (picocli, opt-in), environment-variable defaults surfaced in help (the lazy field
   default `sys.env` idiom already covers the behavior), Scala 2 support (not planned: the
   design depends on Scala 3 inline derivation).
