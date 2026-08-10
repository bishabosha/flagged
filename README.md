# Flagged

Command-line argument parsing and schema validation for Scala 3. Supports derivation of Parsers and automatic help-text from case class/enum or methods.

## Derived sources

The library sources on this branch are mechanically derived from the capture-checked sources on
the [`mutable-spec`](https://github.com/bishabosha/flagged/tree/mutable-spec) branch, where the
parser engine and other mutable internals are verified under the experimental `captureChecking`
and `separationChecking` language modes. The `Uncheck` rewrite in that branch's `build.mill`
strips the capture-checking surface — the language imports, `update`/`consume` modifiers, capture
annotations (`^`, `^{...}`, `->`), and `caps.*` calls — producing the equivalent unchecked code
here, so the published artifacts carry no dependency on experimental standard-library API. Make
source changes on `mutable-spec` and re-derive them with `mill deriveStable` rather than editing
these files directly.

## Why a new library?

I created Flagged to be a candidate library for the [Scala Toolkit](https://github.com/scala/toolkit).
The initial motivation was a lack of momentum on issue [scala/toolkit#65](https://github.com/scala/toolkit/issues/65), on which discussion paused (since July 2025) after a request to compare [com-lihaoyi/mainargs](https://github.com/com-lihaoyi/mainargs) and [alexarchambault/case-app](https://github.com/alexarchambault/case-app). This library aims to provide the best aspects of both libraries (in particular arbitrary nested-subcommands) with as little manual setup as possible.

So my personal goals with introducing Flagged was to combine the feature set of both libraries but with a Scala-3-first design:
- 100% declarative setup, with a single entry point to "run or exit".
- support arbitrary nested subcommands (with `--help` scoped to the current subcommand)
- use standard language features as much as possible
- validate the construction of the schema at compiletime
- Command schemas should be composable (e.g. embedding commands from another classpath)
- introduce candidate standard APIs, compatible with `inline` derivation,
  for reflecting on default parameters and annotations.
- limit use of low level macros (ideally zero if the annot/default reflection
  API becomes compiler builtins).
- support unique schema derivation features of both mainargs and case-app.
- follow standards for option parsing conventions

## Example

```scala
import flagged.*

@cmd(help = "Greet someone from the command line")
case class Greet(
    @opt(help = "Who to greet", short = 'n') name: String = "world",
    @opt(help = "Add excitement", short = 'e') excited: Boolean = false,
    @opt(help = "How many times to greet", short = 'r') repeat: Int = 1
) derives Parser.Command

@main def greet(args: String*): Unit =
  val cfg = Flagged.parseOrExit[Greet](args)
  for _ <- 1 to cfg.repeat do
    println(s"Hello, ${cfg.name}${if cfg.excited then "!" else "."}")
```

equivalent, using methods:

```scala
import flagged.*

@cmd(help = "Greet someone from the command line")
def greet(
    @opt(help = "Who to greet", short = 'n') name: String = "world",
    @opt(help = "Add excitement", short = 'e') excited: Boolean = false,
    @opt(help = "How many times to greet", short = 'r') repeat: Int = 1
): Unit = {
  for _ <- 1 to repeat do
    println(s"Hello, ${name}${if excited then "!" else "."}")
}

@main def app(args: String*): Unit =
  Flagged.parseOrExit[this.type](args)
```
Usage:
```console
$ greet -en Jamie -r 2
Hello, Jamie!
Hello, Jamie!

$ greet --repaet 2
greet: unknown option '--repaet' (did you mean '--repeat'?)
Try 'greet --help' for more information.

$ greet --help
Greet someone from the command line

Usage: greet [options]

Options:
  -n, --name <string>  Who to greet (default: world)
  -e, --excited        Add excitement
  -r, --repeat <int>   How many times to greet (default: 1)
  -h, --help           Show this message and exit
```

## Feature set

### Parse semantics

- Supported option syntax: `--name value` and `--name=value`
- short aliases with separate (`-n Jamie`), attached (`-nJamie`), or assignment (`-n=Jamie`) values
- short flags can be combined (`-er`, `-rn 3`)
- options may appear after positionals (permutation, as in GNU getopt).
- Repeated scalar options are last-wins (e.g. for `--verbose=false --verbose`, verbose is true)
  - so shell aliases can set up some default options.
- Repeated options can accumulate via collection types, or `Count` for flags.
- `--` ends option parsing, following [POSIX Utility Syntax Guidelines, guideline 10]:
  the first `--` is dropped and everything after it is positional, even when it begins
  with `-`. Because Flagged permutes, the `--` is honored wherever it appears, matching
  the default behavior of GNU [getopt(3)], Python's argparse, and Rust's clap (strict
  POSIX, without permutation, would stop scanning at the first operand). When a command
  declares a `Trailing` field, `--` instead hands everything after it to that field
  verbatim — the delegation idiom of `ssh host -- cmd` or `docker run img -- cmd`.

[POSIX Utility Syntax Guidelines]: https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap12.html
[POSIX Utility Syntax Guidelines, guideline 10]: https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap12.html
[getopt(3)]: https://man7.org/linux/man-pages/man3/getopt.3.html

### Declaring options

A field with no annotation is a **positional argument by default**, exactly like a
`@scala.main` parameter — so `@main` methods migrate without changing their command line. An
`@opt` annotation (bare, or with any arguments) turns the field into a named option,
`--kebab-cased` after the field name, unless it sets `positional = true` explicitly. An
unannotated `Boolean` reads a positional `true`/`false` token; a pure flag with no value parser
(`Count`, custom `Parser.flag`) cannot be positional, so leaving it unannotated is a compile
error asking for an `@opt`. The one exception is a `derives Parser.Shared` options group: it
cannot contain positionals, so its unannotated fields stay named options.

The field's `Parser` instance decides its shape:

| Field shape | Meaning |
|---|---|
| `x: A` | positional argument (`@main`-style); ordering rules checked at compile time |
| `x: Boolean` | positional `true`/`false` token |
| `@opt x: Boolean` | flag `--x` (also `--x=false`) |
| `@opt x: Count` | counting flag: `-vvv` → `Count(3)` (the `@opt` is required) |
| `@opt x: A` | required option `--x <a>` |
| `@opt x: A = default` | optional, default shown in help |
| `@opt x: Option[A]` | optional, `None` when absent |
| `@opt x: Iterable[A]` | `--x a --x b` repeatable option with `A` as value |
| `@opt(split = ',') x: Iterable[A]` | `--x a,b --x c` repeatable option with `A` as value, token splits on separator char. |
| `@opt(greedy = true) x: Iterable[A]` | `--x a b c --x d` repeatable option that greedily consumes arguments as `A`. |
| `@opt x: Map[K, V]` | repeatable `--x key=value` entries |
| `@opt x: (A, B)` (any tuple of `Value` types, or a case class deriving `Parser.Product`) | fixed multi-token value: `--x 1 2` |
| `x: E` (enum deriving `Parser.CommandGroup`) | nested subcommands |
| `@opt x: E` (enum deriving `Parser.Enumerated`) | value matched by case name (`--color red`) |
| `x: P` (case class deriving `Parser.Shared`) | options group spliced into this command |
| `x: Trailing` | the raw arguments after `--`, verbatim |

(The unannotated value shapes — `Iterable`, `Map`, tuples, `Enumerated` — parse positionally
with the same element semantics.)

All customisation lives in two annotations with named arguments — sparsely mirrored, so an
argument left at its default costs nothing at derivation.

#### `@opt` — on a field (or method parameter)
- `@opt`: parse the field as a named option (any `@opt` means "named" unless it says otherwise),
- `@opt(positional = true)`: keep a field positional while attaching metadata such as `help`,
- `@opt(split = ',')` (divide a repeated option's value at the separator: `--env A,B,C`),
- `@opt(greedy = true)` (a repeated option consumes the following free tokens: `--nums 10 20 99`; compile error if the command also declares positional or subcommand fields, which would make the grammar ambiguous),
- `@opt(name = "...")`: long-name override; `aliases = ("...", ...)` adds more,
- `@opt(short = 'x')`: provide a short-name,
- `@opt(help = "...")`: provide a description,
- `@opt(hidden = true)`: omit option from help, shown by `--help-all`,
- `@opt(group = "...")`: assign an option to a titled help section.

#### `@cmd` — on a command or command group (type, enum case, method, or object)
- marks a method or nested object as a command for `Parser.method`/`Parser.methods`,
- `@cmd(name = "...")`: command-name (or program-name) override; `aliases = ("...", ...)` adds more,
- `@cmd(help = "...")`: provide a description,
- `@cmd(hidden = true)`: omit a subcommand from help, shown by `--help-all`,
- `@cmd(default = true)`: annotate one command of a group — a subcommand case, a `@cmd` method, or a `@cmd` object — parser will route here if no command token is detected.

#### `@version` — on the top-level type
- add a `--version` flag, that will print the version. Data is sourced from either a literal argument (e.g. `@version("0.1.0")`), or bare `@version` reads a given `Versioned` instance when printed.

### Subcommands

Model groups of commands as an enum (or multiple `@cmd` methods in the same object);

a case with a singular field of an enum type (or a `@cmd` annotated object) will be treated as a nested subcommand group.

```scala
@cmd(name = "gitto", help = "gitto — a tiny version control tool")
enum Gitto derives Parser.CommandGroup:
  @cmd(help = "Clone a repository into a new directory")
  case Clone(
      @opt(help = "Repository URL", positional = true) repo: String,
      dir: Option[String] = None, // unannotated: positional, like a @main parameter
      @opt(short = 'd') depth: Option[Int] = None
  )
  @cmd(help = "Manage remotes")
  case Remote(action: RemoteAction)
  @cmd(help = "Show the working tree status")
  case Status(@opt(short = 's') short: Boolean = false)

enum RemoteAction derives Parser.CommandGroup:
  case Add(name: String, url: String)
  case Remove(name: String)

@main def gitto(args: String*): Unit =
  Flagged.parseOrExit[Gitto](args) match
    case Gitto.Clone(repo, dir, depth)             => ???
    case Gitto.Remote(RemoteAction.Add(name, url)) => ???
    case Gitto.Remote(RemoteAction.Remove(name))   => ???
    case Gitto.Status(short)                       => ???
```

Derivation only handles a single class/enum, any field will need its own Parser derived separately.

### Command methods

Annotate methods with `@cmd` to make them reflectable as a command to a derived Parser. Arguments are otherwise handled identically to derivation for a class.

```scala
@cmd(help = "Add an entry")
def add(text: String, @opt urgent: Boolean = false): Int = ... // text: positional, as with @main

@cmd(name = "ls")
def list(@opt all: Boolean = false): List[String] = ...

@main def run(args: String*): Unit =
  val result: Int | List[String] = Flagged.parseOrExit[this.type](args, prog = "todo")
```

A single `@cmd` method will become the name of the root program, otherwise a group of `@cmd` methods will take the name of the scope defining the methods, (overridden with the `prog` argument of `Flagged.parse*` methods.)

### Values, groups, errors

- **Built-in value types:** `String`, `Char`, `Boolean`, the numeric types,
  `BigInt`/`BigDecimal`, `Path`, `File`, `UUID`, the common `java.time` types, and
  `FiniteDuration` (`"30s"`, `"5.minutes"`), following platform availability.
  A custom parser is a one-liner (`Parser.of`), and every parser supports
  `map`/`emap` — including whole commands, which gives cross-field validation before
  your code runs. Flag and repeated shapes are pluggable too (`Parser.flag`,
  `Parser.repeated`), so occurrence bounds and non-empty constraints are expressible.
- **Option groups:** a `derives Parser.Shared` case-class field splices that group's options into the
  surrounding command and reconstructs the group as a value. Groups nest, work inside
  subcommand cases, can be optional (`Option[Group]`), and can be prefixed (`@opt(name = ...)`
  on the field) to be spliced more than once.
- **Errors accumulate:** unknown options, invalid values, missing option values, and
  missing required arguments are collected and reported in one failure, one per line,
  with did-you-mean hints.
- **Help output** shows defaults, required and repeatable markers, titled sections,
  and a `--version` line when declared.

### Entry points and results

`Flagged.parseOrExit` takes any `Seq[String]` and executes the matching entrypoint and returning its result. On `--help` it prints the help screen
and exits 0; on erroneous arguments it prints the error to stderr and exits 2. To
handle aborts yourself, `Flagged.parse` returns a `Result[T, ParseError]` (from
[lampepfl/steps](https://github.com/lampepfl/steps)) instead of exiting, and
`Flagged.help[A]` renders the help text without parsing anything.

### Platforms

Scala 3 only — the design depends on Scala 3 inline derivation. Cross-builds for the
JVM, Scala.js, and Scala Native; `Parser` instances follow platform availability
(`java.time` types are JVM-only, `Path` is JVM and Scala Native).

## How-to

Short recipes for common goals.

### Parse a custom value type

Define a `Parser.Value` given with `Parser.of`; the type name you pass becomes the
`<metavar>` in help output. Instances are picked up by derivation for fields of that
type:

```scala
given Parser.Value[Port] = Parser.of[Port]("port")(s =>
  s.toIntOption.filter(p => p > 0 && p < 65536) match
    case Some(p) => Result.Ok(Port(p))
    case None    => Result.Err((s"'$s' is not a valid port"))
```

For simple cases, `map`/`emap` on an existing parser also works, and preserves the
parser's shape.

### Accept a value from a fixed set of names

An enum with parameterless cases can derive a by-name value parser:

```scala
enum LogLevel derives Parser.Enumerated:
  case Debug, Info, Warn, Error
```

gives you `--level warn` (kebab-cased, matched exactly) and the metavar
`<debug|info|warn|error>`.

### Embed a command defined elsewhere

A command-group case whose sole field carries a full `Parser.Command` embeds that
command wholesale — options, positionals, trailing and all. The case's grammar *is*
the embedded command's (safe, because subcommand dispatch hands the remaining tokens
to it rather than merging grammars), and the parse result is wrapped in the case:

```scala
// from another module or classpath
case class ExternalTool(@opt(short = 'f') force: Boolean = false, target: String)
    derives Parser.Command

enum Cli derives Parser.CommandGroup:
  case Build(release: Boolean = false)
  @cmd(name = "ext", help = "Run the external tool")
  case External(tool: ExternalTool)
// cli ext -f thing   →   Cli.External(ExternalTool(force = true, target = "thing"))
```

`@cmd(name/help/hidden)` on the *case* renames and documents the embedded command
locally; annotations on the field itself are a compile error (they could not take
effect).

### Share options between commands

A field whose type derives `Parser.Shared` splices that group's options into the
surrounding command — they parse as if declared inline, and the group is
reconstructed as a value. `Shared` derivation enforces the invariants that make
splicing always safe (no positional, trailing, subcommand, or greedy fields), at
compile time:

```scala
case class LogOpts(@opt(short = 'q') quiet: Boolean = false, logLevel: String = "info") derives Parser.Shared

case class Serve(port: Int = 8080, logging: LogOpts = LogOpts()) derives Parser.Command
// serve --port 9000 -q --log-level debug
```

Groups nest and work inside subcommand cases. An `Option[LogOpts]` field parses to
`None` unless one of the group's options occurs; `@opt(name = "log")` on the field
prefixes the group's option names (`--log-quiet`), so the same group can be spliced
more than once. Spliced groups cannot contain positional fields.

### Forward arguments to another program

Declare a `Trailing` field: everything after `--` will be accumulated there (useful
for wrapper programs like in `docker run img -- cmd args...`):

```scala
case class Run(@opt(short = 'i') image: String = "alpine", cmd: Trailing = Trailing()) derives Parser.Command
// run -i ubuntu -- echo --not-an-option   →   Run("ubuntu", Trailing(Vector("echo", "--not-an-option")))
```

An `Option[Trailing]` field distinguishes an absent `--` (`None`) from a
present-but-empty one (`Some(Trailing())`). One trailing field per command; in
help it appears as `[-- <args>]` in the usage line.

### Take several values for one option

- product types like Tuple with multiple arguments to one option (one parser per field)
- use `@opt(split = ...)` to build a collection from a single argument (all elements share the same parser)
- use `@opt(greedy = true)` for a collection of arbitrary length (all elements share the same parser)

```scala
case class Render(
    @opt(short = 'p') point: (Int, Int) = (0, 0),      // --point 3 4
    @opt(split = ',') env: List[String] = Nil,         // --env A=1,B=2   (also -e X --env Y,Z)
    @opt(greedy = true) nums: List[Int] = Nil          // --nums 10 20 99 7
) derives Parser.Command
```

### Constrain repetition or flag occurrences

`Parser.repeated` collects each occurrence with an element `Parser.Value` and
combines them with a function of your choice — which may fail, so constraints are
expressible (it is also invoked empty when the argument is absent):

```scala
given Parser.Repeated[NonEmpty] = Parser.repeated[Int, NonEmpty](l =>
  if l.isEmpty then Result.Err("expected at least one occurrence") else Result.Ok(NonEmpty(l.toList)))
```

`Parser.flag` does the same for flags, building the value from the occurrence count
(this is how `Boolean` and `Count` are defined):

```scala
given Parser.Flag[Verbosity] = Parser.flag(n =>
  if n <= 3 then Result.Ok(Verbosity(n)) else Result.Err(s"at most 3 occurrences (got $n)"))
```

### Validate across fields

`emap` on a command parser runs after the fields parse and before your code sees the
value, so cross-field rules report through the normal error channel:

```scala
case class Fetch(url: String, tls: Boolean = false, certFile: Option[Path] = None)

given Parser.Command[Fetch] = Parser.Command.derived[Fetch].emap(cfg =>
  if cfg.tls && cfg.certFile.isEmpty then Result.Err("--tls requires --cert-file") else Result.Ok(cfg))
```

### Make a subcommand optional or default

An `Option[E]`-typed command field makes the command optional, and a field default
(`action: Action = Action.List`) works too. `@cmd(default = true)` on one command of
a group — an enum case, a `@cmd` method, or a `@cmd` object — marks the command run
when no command token is given, with the remaining arguments forwarded to it. Every
group takes its own default, so a group that is itself the default keeps forwarding
down to its default; a second `@cmd(default = true)` in the same group is a compile
error.

### Handle results without exiting

In tests, or when embedding a CLI in a bigger program, `Flagged.parse` returns a
value instead of exiting:

```scala
Flagged.parse[Greet](Seq("--name", "Jamie")) match
  case Result.Ok(cfg)                            => run(cfg)
  case Result.Err(ParseError.Help(text))         => println(text)
  case Result.Err(ParseError.Failure(msg, hint)) => logger.error(msg)
```

`import flagged.*` brings `Result`, `Ok`, and `Err` into scope, and the full steps
toolkit (`map`, `getOrElse`, `toEither`, direct-style `Result:` blocks) is available
on parse results.

### Use Flagged from a script

`parseOrExit` takes any `Seq[String]`, so it works wherever your arguments come
from; pass `prog` to set the program name when there is no `@cmd(name = ...)` annotation:

```scala
// backup.sc — run with scala CLI
import flagged.*

case class Backup(
    @opt(help = "Destination directory", short = 'd') dest: String,
    @opt(help = "Print actions without copying", short = 'n') dryRun: Boolean = false,
    sources: List[String] = Nil // unannotated: repeated positional
) derives Parser.Command

val cfg = Flagged.parseOrExit[Backup](args.toSeq, prog = "backup")
```

## Performance

Full methodology and tables are in [`bench/`](bench/results.md); the comparison
below is against mainargs 0.7.8 and case-app 2.1.0 on the same inputs (Apple M3 Max,
Temurin JDK 25, Scala 3.8.3).

**Compile time** is probably what people care most about for a derivation-heavy library. Derivation adds ~43–94 ms per file over a plain-data baseline across the
benchmark scenarios, against ~165–241 ms for mainargs and ~450–526 ms for case-app.
Cost grows linearly with field count, at ~1.2 ms per field, and stays below mainargs
at every measured size up to 128 fields.

**The one-shot cost** — a CLI process constructs its parser once and parses once, and
Flagged is measured on both halves: construction plus parse comes to 0.35 µs on a small
command and 2.3 µs on a docker-style subcommand CLI, fastest of the six measured
libraries end to end (5.5× ahead of mainargs, 23–112× ahead of case-app, scopt, scallop,
and picocli on the realistic scenario). Construction alone is the one place Flagged is
not the quickest — it builds the complete parse-ready model up front, lookup tables
included — which the parse numbers repay within the first parse.

**Parse latency:** on non-trivial argument lists Flagged parses in 0.16–0.78 µs,
1.7–82× faster than mainargs and case-app on the same scenarios, allocating 4.5–184×
less — the widest gap on the `realistic` scenario, a docker-style subcommand CLI
(13× vs mainargs, 82× vs case-app; scopt, scallop, and picocli, measured on the same
scenario, come out 70–204× slower than Flagged); the hot path aims to allocate only what is necessary to build the target data (further improvements could be made to avoid boxing). The same holds on non-JVM platforms: Flagged
is the fastest of the three on every scenario on Scala.js, WebAssembly, and Scala
Native.

**Comparison to hand-written:**
- A hand-written parser at feature-parity with Flagged is still 4.0–21× faster than the
derived one
- The typical quick hand-rolled parser (case class of defaults, pattern match for known tokens on a `List[String]`, copying per parsed option) is faster on small grammars but 2.1× slower at 25 options,
since its cost grows with options × tokens where the engine's grows with tokens.
- `@main` def (scala builtin parsing) is the fastest by far (but only handles positional arguments)

## Comparison with other libraries

[`docs/comparison.md`](docs/comparison.md) has a feature matrix covering mainargs,
case-app, scopt, scallop, and picocli, the deliberate behavioral differences from
mainargs/case-app (each pinned down in `ParitySuite` against the other library's
documented behavior), and Flagged's known gaps, ranked — shell completions and
unrecognized-argument passthrough being the two at the top.

## Local publishing

Until Flagged is published, to try it out, clone this repository and run `./mill flagged.jvm.test`,
or any of the demos in `examples/` (`./mill examples.runMain examples.greetMain --help`).

To use it in a project, run `./mill flagged._.publishLocal` (or just `./mill flagged.jvm.publishLocal` for quickest access)
