# Flagged

Command-line argument parsing and validation for Scala 3. Enables you to define the interface of
a CLI app as either pure data or methods, and the library derives a parser, schema validation, and help text automatically.

## Goals

Flagged was created to be a candidate library for the [Scala Toolkit](https://github.com/scala/toolkit), combining the strengths of two commonly-used Scala libraries for CLI argument parsing:
- [com-lihaoyi/mainargs](https://github.com/com-lihaoyi/mainargs)
- [alexarchambault/case-app](https://github.com/alexarchambault/case-app)

## Why a new library?

The initial motivation was a lack of momentum on issue [scala/toolkit#65](https://github.com/scala/toolkit/issues/65), on which discussion paused (since July 2025) after a request to compare mainargs and case-app.

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

@help("Greet someone from the command line")
case class Greet(
    @short('n') @help("Who to greet") name: String = "world",
    @short('e') @help("Add excitement") excited: Boolean = false,
    @short('r') @help("How many times to greet") repeat: Int = 1
) derives Parser.Command

@main def greet(args: String*): Unit =
  val cfg = Flagged.parseOrExit[Greet](args)
  for _ <- 1 to cfg.repeat do
    println(s"Hello, ${cfg.name}${if cfg.excited then "!" else "."}")
```

equivalent, using methods:

```scala
import flagged.*

@run
@help("Greet someone from the command line")
def greet(
    @short('n') @help("Who to greet") name: String = "world",
    @short('e') @help("Add excitement") excited: Boolean = false,
    @short('r') @help("How many times to greet") repeat: Int = 1
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

Supported option syntax: `--name value` and `--name=value`; short aliases with
separate (`-n Jamie`), attached (`-nJamie`), or `=` values; short-flag bundling
(`-er`, `-rn 3`); `-` and negative numbers treated as values; options may appear
after positionals (permutation, as in GNU getopt). Repeated scalar options are
last-wins — `--verbose=false --verbose` is true — which keeps shell aliases
composable; accumulation is opt-in through collection types and `Count`.

`--` ends option parsing, following [POSIX Utility Syntax Guidelines, guideline 10]:
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

Each case class field becomes a named option, `--kebab-cased` after the field name.
The field's `Parser` instance decides its shape:

| Field shape | Meaning |
|---|---|
| `x: Boolean` | flag `--x` (also `--x=false`) |
| `x: Count` | counting flag: `-vvv` → `Count(3)` |
| `x: A` | required option `--x <a>` |
| `x: A = default` | optional, default shown in help |
| `x: Option[A]` | optional, `None` when absent |
| `x: List[A]` (any collection with a `Factory`) | repeatable |
| `x: Map[K, V]` | repeatable `--x key=value` entries |
| `x: (A, B)` (any tuple of `Value` types, or a case class deriving `Parser.Product`) | fixed multi-token value: `--x 1 2` |
| `x: E` (enum deriving `Parser.CommandGroup`) | nested subcommands |
| `x: E` (enum deriving `Parser.Enumerated`) | value matched by case name (`--color red`) |
| `x: P` (case class deriving `Parser.Shared`) | options group spliced into this command |
| `x: Trailing` | the raw arguments after `--`, verbatim |
| `@positional x: A` | positional argument (same rules) |

Annotations fine-tune the rest: `@name` (long-name override and aliases), `@short`,
`@help`, `@positional`, `@hidden` (omitted from help, shown by `--help-all`),
`@group` (titled help sections), `@split` (divide a repeated option's value at a
separator, `,` by default: `--env A,B,C`), `@greedy` (a repeated option consumes the
following free tokens: `--nums 10 20 99`; compile error if the command also declares
positional or subcommand fields, which would make the grammar ambiguous), `@version`
(adds `--version` via a `Versioned` instance), and `@default` (the command run when
no command token is given).

### Subcommands

Model groups of commands as an enum (or multiple `@run` methods in the same object);

a case with a singular field of an enum type (or a `@run` annotated object) will be treated as a nested subcommand group.

```scala
@name("gitto")
@help("gitto — a tiny version control tool")
enum Gitto derives Parser.CommandGroup:
  @help("Clone a repository into a new directory")
  case Clone(
      @positional @help("Repository URL") repo: String,
      @positional dir: Option[String] = None,
      @short('d') depth: Option[Int] = None
  )
  @help("Manage remotes")
  case Remote(action: RemoteAction)
  @help("Show the working tree status")
  case Status(@short('s') short: Boolean = false)

enum RemoteAction derives Parser.CommandGroup:
  case Add(@positional name: String, @positional url: String)
  case Remove(@positional name: String)

@main def gitto(args: String*): Unit =
  Flagged.parseOrExit[Gitto](args) match
    case Gitto.Clone(repo, dir, depth)             => ???
    case Gitto.Remote(RemoteAction.Add(name, url)) => ???
    case Gitto.Remote(RemoteAction.Remove(name))   => ???
    case Gitto.Status(short)                       => ???
```

Derivation only handles a single class/enum, any field will need its own Parser derived separately.

### Command methods

Annotate methods with `@run` to make them reflectable as a command to a derived Parser. Arguments are otherwise handled identically to derivation for a class.

```scala
@run @help("Add an entry")
def add(@positional text: String, urgent: Boolean = false): Int = ...

@run @name("ls")
def list(all: Boolean = false): List[String] = ...

@main def run(args: String*): Unit =
  val result: Int | List[String] = Flagged.parseOrExit[this.type](args, prog = "todo")
```

A single `@run` method will become the name of the root program, otherwise a group of `@run` methods will take the name of the scope defining the methods, (overridden with the `prog` argument of `Flagged.parse*` methods.)

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
  subcommand cases, can be optional (`Option[Group]`), and can be prefixed (`@name`
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
    case Some(p) => Ok(Port(p))
    case None    => Err(s"'$s' is not a valid port"))
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
case class ExternalTool(@short('f') force: Boolean = false, @positional target: String)
    derives Parser.Command

enum Cli derives Parser.CommandGroup:
  case Build(release: Boolean = false)
  @name("ext") @help("Run the external tool")
  case External(tool: ExternalTool)
// cli ext -f thing   →   Cli.External(ExternalTool(force = true, target = "thing"))
```

`@name`/`@help`/`@hidden` on the *case* rename and document the embedded command
locally; annotations on the field itself are a compile error (they could not take
effect).

### Share options between commands

A field whose type derives `Parser.Shared` splices that group's options into the
surrounding command — they parse as if declared inline, and the group is
reconstructed as a value. `Shared` derivation enforces the invariants that make
splicing always safe (no positional, trailing, subcommand, or `@greedy` fields), at
compile time:

```scala
case class LogOpts(@short('q') quiet: Boolean = false, logLevel: String = "info") derives Parser.Shared

case class Serve(port: Int = 8080, logging: LogOpts = LogOpts()) derives Parser.Command
// serve --port 9000 -q --log-level debug
```

Groups nest and work inside subcommand cases. An `Option[LogOpts]` field parses to
`None` unless one of the group's options occurs; `@name("log")` on the field
prefixes the group's option names (`--log-quiet`), so the same group can be spliced
more than once. Spliced groups cannot contain positional fields.

### Forward arguments to another program

Declare a `Trailing` field: everything after `--` lands in it verbatim, unparsed —
the delegation idiom of `docker run img -- cmd args...`:

```scala
case class Run(@short('i') image: String = "alpine", cmd: Trailing = Trailing(Nil)) derives Parser.Command
// run -i ubuntu -- echo --not-an-option   →   Run("ubuntu", Trailing(List("echo", "--not-an-option")))
```

An `Option[Trailing]` field distinguishes an absent `--` (`None`) from a
present-but-empty one (`Some(Trailing(Nil))`). One trailing field per command; in
help it appears as `[-- <args>]` in the usage line.

### Take several values for one option

Three shapes, each compile-checked. A tuple (or `derives Parser.Product` case class)
field consumes a fixed number of consecutive tokens; `@split` divides one value into
collection elements; `@greedy` lets a repeated option consume the following free
tokens:

```scala
case class Render(
    @short('p') point: (Int, Int) = (0, 0),      // --point 3 4
    @split env: List[String] = Nil,              // --env A=1,B=2   (also -e X --env Y,Z)
    @greedy nums: List[Int] = Nil                // --nums 10 20 99 7
) derives Parser.Command
```

Products are fixed-arity by design — the arity is the tuple's size, shown in help as
one metavar per element — and repetition is last-wins, like single values. `@greedy`
consumption stops at the next option-like token or `--`, and the command may not
declare positional or subcommand fields (a compile error: those would compete for
the same free tokens); pair it with `Trailing` when arguments must be forwarded.

### Constrain repetition or flag occurrences

`Parser.repeated` collects each occurrence with an element `Parser.Value` and
combines them with a function of your choice — which may fail, so constraints are
expressible (it is also invoked empty when the argument is absent):

```scala
given Parser.Repeated[NonEmpty] = Parser.repeated[Int, NonEmpty](l =>
  if l.isEmpty then Err("expected at least one occurrence") else Ok(NonEmpty(l.toList)))
```

`Parser.flag` does the same for flags, building the value from the occurrence count
(this is how `Boolean` and `Count` are defined):

```scala
given Parser.Flag[Verbosity] = Parser.flag(n =>
  if n <= 3 then Ok(Verbosity(n)) else Err(s"at most 3 occurrences (got $n)"))
```

### Validate across fields

`emap` on a command parser runs after the fields parse and before your code sees the
value, so cross-field rules report through the normal error channel:

```scala
case class Fetch(url: String, tls: Boolean = false, certFile: Option[Path] = None)

given Parser.Command[Fetch] = Parser.Command.derived[Fetch].emap(cfg =>
  if cfg.tls && cfg.certFile.isEmpty then Err("--tls requires --cert-file") else Ok(cfg))
```

### Make a subcommand optional or default

An `Option[E]`-typed command field makes the command optional, and a field default
(`action: Action = Action.List`) works too. At the top level, `@default` on one enum
case marks the command run when no command token is given, with the remaining
arguments forwarded to it.

### Handle results without exiting

In tests, or when embedding a CLI in a bigger program, `Flagged.parse` returns a
value instead of exiting:

```scala
Flagged.parse[Greet](Seq("--name", "Jamie")) match
  case Ok(cfg)                            => run(cfg)
  case Err(ParseError.Help(text))         => println(text)
  case Err(ParseError.Failure(msg, hint)) => logger.error(msg)
```

`import flagged.*` brings `Result`, `Ok`, and `Err` into scope, and the full steps
toolkit (`map`, `getOrElse`, `toEither`, direct-style `Result:` blocks) is available
on parse results.

### Use Flagged from a script

`parseOrExit` takes any `Seq[String]`, so it works wherever your arguments come
from; pass `prog` to set the program name when there is no `@name` annotation:

```scala
// backup.sc — run with scala-cli
import flagged.*

case class Backup(
    @short('d') @help("Destination directory") dest: String,
    @short('n') @help("Print actions without copying") dryRun: Boolean = false,
    @positional sources: List[String] = Nil
) derives Parser.Command

val cfg = Flagged.parseOrExit[Backup](args.toSeq, prog = "backup")
```

## Performance

Full methodology and tables are in [`bench/`](bench/results.md); the comparison
below is against mainargs 0.7.8 and case-app 2.1.0 on the same inputs (Apple M3 Max,
Temurin JDK 25, Scala 3.8.3).

**Compile time** is probably what people care most about for a derivation-heavy library. Derivation adds ~45–120 ms per file over a plain-data baseline across the
benchmark scenarios, against ~143–207 ms for mainargs and ~388–442 ms for case-app.
Cost grows linearly with field count, at under 2 ms per field.

**Parse latency:** on non-trivial argument lists Flagged parses in 0.09–0.70 µs,
1.7–95× faster than mainargs and case-app on the same scenarios, allocating 3.8–179×
less — the widest gap on the `realistic` scenario, a docker-style subcommand CLI
(14× vs mainargs, 95× vs case-app; scopt, scallop, and picocli, measured on the same
scenario at runtime only, come out 81–232× slower than Flagged); the hot path aims to allocate only what is necessary to build the target data (further improvements could be made to avoid boxing). The same holds on non-JVM platforms: Flagged
is the fastest of the three on every scenario on Scala.js, WebAssembly, and Scala
Native.

**Comparison to hand-written:**
- A hand-written parser at feature-parity with Flagged is still 2.2–5.2× faster than the
derived one
- The typical quick hand-rolled parser (case class of defaults, pattern match for known tokens on a `List[String]`, copying per parsed option) is faster on small grammars but 2.5× slower at 25 options,
since its cost grows with options × tokens where the engine's grows with tokens.
- `@main` def (scala builtin parsing) is the fastest by far (but only handles positional arguments)

## Comparison with other libraries

[`docs/comparison.md`](docs/comparison.md) has a feature matrix covering mainargs,
case-app, scopt, scallop, and picocli, the deliberate behavioral differences from
mainargs/case-app (each pinned down in `ParitySuite` against the other library's
documented behavior), and Flagged's known gaps, ranked — shell completions and
unrecognized-argument passthrough being the two at the top.

## Getting Flagged

Flagged is not yet published to a Maven repository. To try it out, clone this
repository and run `./mill flagged.jvm.test`, or any of the demos in `examples/`
(`./mill examples.runMain examples.greetMain --help`); to use it in a project,
vendor `flagged/src` plus the source folders for your platform
(`flagged/src-jvm`, `flagged/src-js`, `flagged/src-native`,
`flagged/src-jvm-native`).
