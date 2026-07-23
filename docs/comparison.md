# Comparison with other CLI parsing libraries

Comparison against the two Scala derivation libraries flagged is most directly an alternative
to — mainargs (0.7.7–0.7.8) and case-app (2.x) — and the most-used non-derivation libraries:
scopt (4.1.0), scallop (5.1.0), and picocli (4.7.6, the de-facto Java standard).

Sources: the mainargs and case-app entries are based on their documentation and test suites,
and overlapping behaviors are pinned down in `flagged/test/src/flagged/ParitySuite.scala` —
each test names the library whose documented behavior it was checked against. The scopt,
scallop, and picocli entries are based on their documentation plus empirical spot-checks of
the syntax behaviors (the same probes that back the `realistic` benchmark encodings); they are
not pinned as tests. JMH benchmarks comparing derivation compile time and parse
latency/allocation live in `bench/` (see `bench/README.md`); all six libraries meet in the
`realistic` runtime scenario.

## The libraries

| Library | Definition model | Validation | Scala / platforms |
| --- | --- | --- | --- |
| flagged | case class / enum `derives`, `@run` command methods (`Mirror` + inline, three minimal macros) | compile time | 3; JVM, JS, Native |
| mainargs | `@main` methods or case class (macros) | some compile, most runtime | 2.12–3; JVM, JS, Native |
| case-app | case class (shapeless on Scala 2, macros on 3) | runtime | 2.12–3; JVM, JS, Native |
| scopt | config class + `OParser` builder, hand-wired | runtime | 2.11–3; JVM, JS, Native |
| scallop | `ScallopConf` subclass DSL, coupled to parsing (built and `verify()`d per argument list) | runtime, at `verify()` | 2.12–3; JVM, JS, Native |
| picocli | annotated Java-first classes, runtime reflection | runtime, at model build | Java 8+ (usable from Scala); JVM only (GraalVM native-image via codegen) |

## Feature matrix

The **Others** column names the libraries that have the feature and how it is spelled there;
a library not named does not have it.

| Feature | flagged | Others |
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
| Multi-value arity (`--point 1 2 3`) | no — one value per occurrence | scallop multi-value options; picocli `arity = "2..3"` |
| Value splitting (`--env A,B,C` → elements) | via custom value parser only | scopt (its native collection format); picocli `split` regex |
| `Map[K,V]` (`--x k=v`) | yes | mainargs; scopt (comma-separated pairs); scallop `props` (`-Dk=v`); picocli |
| `Option[T]` | yes | mainargs, case-app; scallop `ScallopOption[T]`; picocli (incl. `Optional<T>`); scopt: config fields, options optional by default |
| Defaults from field defaults | yes (lazy) | mainargs, case-app (lazy); scopt initial config instance; scallop `default =` parameter; picocli field initializers |
| Environment-variable defaults | via lazy field defaults (not shown in help) | picocli `${env:VAR}`, default-value providers |
| Positional arguments | `@positional`, ordering checked | mainargs `positional = true`; case-app: the remaining args; scopt `arg[T]`; scallop `trailArg[T]`; picocli `@Parameters(index = ...)` |
| Variadic positionals, typed | repeated `@positional` field | mainargs `Leftover[T]`; scopt `.unbounded()`; scallop `trailArg[List[T]]`; picocli `index = "1..*"`; case-app `RemainingArgs` (untyped) |
| Number-only options (`-5`, as `tail -5`) | no — `-<digits>` is a value, by design | scallop `number()` |
| Subcommands | enums or `@run` objects, arbitrarily nested | scallop, picocli: arbitrarily nested; case-app: nested via multi-word names; scopt `cmd().children`, nestable into one flat config; mainargs: one level |
| Shared option groups (splicing) | yes, nested, spliced into the command | mainargs `TokensReader.Class`; case-app `@Recurse`; picocli `@Mixin`; scopt: builder fragments (values stay flat) |
| Optional group | `Option[Group]` | case-app; picocli `@ArgGroup` multiplicity `0..1` |
| Prefixed group names | `@name` on the group field | case-app `@Recurse("prefix")` |
| Declarative cross-option constraints | no — `emap` checks after parse | scallop `conflicts`/`codependent`/`requireOne`; picocli `@ArgGroup` exclusive / co-occurring |
| `--help` | any position, per level | case-app, scallop, picocli: per command; mainargs: first token, top level only; scopt: one usage screen for the whole tree |
| Help markers: defaults / required / repeatable | yes / yes / yes | case-app: `*` for repeatable; picocli: opt-in templates (`${DEFAULT-VALUE}`, `requiredOptionMarker`) |
| Did-you-mean suggestions | commands and options | picocli ("possible solutions") |
| Error aggregation | all error kinds together | case-app: all kinds; mainargs: missing + unknown + duplicate; scopt, picocli: unknown options together, else first; scallop: first error |
| Duplicate name detection | compile time (constant names) + construction | case-app: opt-in runtime (`ensureNoDuplicates`); scallop, picocli: at model build/`verify()`; mainargs: none documented |
| Hidden options / commands | `@hidden`, revealed by `--help-all` | mainargs, scallop, picocli `hidden = true`; case-app `@Hidden`, `--full-help`; scopt `.hidden()` |
| Help sections | `@group` (also on spliced groups) | case-app `@Group`, sorted; scallop `group()`; picocli `@ArgGroup(heading = ...)` |
| Name & version in help | `@version` + a `Versioned` instance, `--version` | case-app `@AppName`/`@AppVersion`; scopt `head(...)`/`version(...)`; scallop `version(...)`; picocli `@Command(version = ...)` |
| Multiple names per option | repeatable `@name` + one short | case-app, picocli: any number; scopt: long + short + `abbr("ab")`; mainargs: one long + one short |
| Command aliases / default command | repeatable `@name` / `@default` (with arg forwarding) | case-app: both; scallop: aliases; picocli: `aliases` / parent command runs |
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
  both doubles the effective namespace and hides collisions. Explicit `@name` values are matched
  verbatim in flagged, mainargs, and case-app alike.
- **No automatic short option for single-letter fields.** mainargs and case-app turn a field `v`
  into `-v` (short-only). flagged requires `@short('v')`; a single-letter field is `--v`.
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
   case, and `@default`-command forwarding covers the leading-command case). Needs an API-surface
   decision: a parse-time flag does not fit the typed model as well as a `Passthrough`-shaped
   field would.
3. **Negatable flags.** An auto-generated `--no-verbose` complement (picocli `negatable`,
   scallop `toggle`). flagged spells the negative form `--verbose=false`; a flag-shape variant
   could add the `--no-` spelling and show it in help.
4. **Declarative cross-option constraints.** Mutually-exclusive and co-occurring option groups
   (picocli `ArgGroup`, scallop `conflicts`/`codependent`/`requireOne`). `emap` expresses the
   check but runs after parse — schema-level constraints could be reflected in usage output
   and completions.
5. **Multi-value arity and value splitting.** One occurrence consuming several values
   (`--point 1 2 3`; picocli `arity`, scallop multi-value options) and splitting one token into
   elements (`--env A,B,C`; picocli `split`, scopt's collection format). flagged parses exactly
   one value per occurrence; splitting is expressible only inside a custom value parser.
6. **Low priority.** `--usage` (condensed help), alphabetical help sorting toggle, pluggable name
   mapper (snake_case), argument index tracking (`Indexed`), number-only options (scallop's
   `tail -5` style — flagged reserves `-<digits>` as values by design), abbreviated long-option
   matching (picocli, opt-in), environment-variable defaults surfaced in help (the lazy field
   default `sys.env` idiom already covers the behavior), Scala 2 support (not planned: the
   design depends on Scala 3 inline derivation).
