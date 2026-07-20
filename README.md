# flagged

Command-line argument parsing for Scala 3. You describe your CLI as plain data — a
case class for options, an enum for subcommands — and flagged derives the parser, the
`--help` screens, and the error messages at compile time.

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
  (1 to cfg.repeat).foreach(_ => println(s"Hello, ${cfg.name}${if cfg.excited then "!" else "."}"))
```

Running the program:

```console
$ greet -en Jamie -r 2
Hello, Jamie!
Hello, Jamie!

$ greet --help
Greet someone from the command line

Usage: greet [options]

Options:
  -n, --name <string>  Who to greet (default: world)
  -e, --excited        Add excitement
  -r, --repeat <int>   How many times to greet (default: 1)
  -h, --help           Show this message and exit

$ greet --repaet 2
greet: unknown option '--repaet' (did you mean '--repeat'?)
Try 'greet --help' for more information.
```

Supported option syntax: `--name value` and `--name=value`; short aliases with
separate (`-n Jamie`), attached (`-nJamie`), or `=` values; flag bundling (`-er`,
`-rn 3`); `--` to end option parsing — or, when a field of type `Trailing` is
declared, to hand everything after it to that field verbatim (`run img -- cmd args`);
`-` and negative numbers are treated as values.

### The meaning of `--`

flagged follows the GNU convention: the first `--` is a delimiter that is itself
dropped, and every argument after it is treated as a positional value, even when it
begins with `-` ([POSIX Utility Syntax Guidelines, guideline 10]; [getopt(3p)]
consumes the `--` token). Because flagged *permutes* — options may appear after
positionals — the `--` is honored wherever it appears on the line, so
`prog a -- b c` yields the positionals `a b c`. Strict POSIX differs here: without
permutation, option scanning ends at the first operand and a later `--` is a literal
argument. C programs toggle between the two with `POSIXLY_CORRECT` (see [glibc's
Program Argument Syntax Conventions] and [getopt(3)]); flagged's behavior matches the
default of GNU getopt, Python's argparse, and Rust's clap.

When a command declares a `Trailing` field, `--` instead hands everything after it
to that field verbatim — the delegation idiom of `ssh host -- cmd` or
`docker run img -- cmd`, which POSIX does not specify but which layers on the same
delimiter rule.

[POSIX Utility Syntax Guidelines, guideline 10]: https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap12.html
[getopt(3p)]: https://pubs.opengroup.org/onlinepubs/9699919799/functions/getopt.html
[glibc's Program Argument Syntax Conventions]: https://www.gnu.org/software/libc/manual/html_node/Argument-Syntax.html
[getopt(3)]: https://man7.org/linux/man-pages/man3/getopt.3.html

## Getting flagged

flagged is not yet published to a Maven repository. To try it out, clone this repository
and run `scala-cli test .` or any of the demos in `examples/`; to use it in a project,
vendor the `src/flagged` directory. flagged cross-builds for the JVM, Scala.js, and
Scala Native (`scala-cli test . --js` / `--native`); the platform-specific sources use
scala-cli's `target.platform` directives, which need power mode
(`scala-cli config power true` once).

## Declaring options

Each case class field becomes a named option, `--kebab-cased` after the field name.
Semantics are instance-driven: a field whose type has a `Parser` given becomes nested
subcommands or a spliced options group; value shapes parse as option values, with
the parser's *schema* deciding whether the option is a flag, takes one value, or
repeats:

| Field shape | Meaning |
|---|---|
| `x: Boolean` | flag `--x` (also `--x=false`); always optional, repeatable — the last mention wins |
| `x: Count` (or any `Parser.Flag[A]`) | counting flag: `-vvv` → `Count(3)` |
| `x: A` (`Parser.Value[A]`) | required option `--x <a>` |
| `x: A = default` | optional, default shown in help |
| `x: Option[A]` | optional, `None` when absent |
| `x: Option[Boolean]` | optional flag: absent → `None`, `--x` → `Some(true)`, `--x=false` → `Some(false)` |
| `x: A` (`Parser.Repeated[A]`, e.g. `List`/`Seq`/`Vector`/`Set`) | repeatable |
| `x: Map[K, V]` | repeatable `--x key=value` entries |
| `x: E` (enum `E derives Parser.CommandGroup`) | nested subcommands |
| `x: P` (case class `P derives Parser.Command`) | options group spliced into this command |
| `x: Option[P]` | optional group: `None` unless one of its options occurs |
| `x: Trailing` (or any `Parser.Trailing[A]`) | the raw arguments after `--`, verbatim |
| `@positional x: A` | positional argument (same rules) |

A `Trailing` field collects the raw arguments after `--`, verbatim — nothing after
the delimiter is parsed as an option. This is the delegation idiom of
`docker run img -- cmd args...`:

```scala
case class Run(@short('i') image: String = "alpine", cmd: Trailing = Trailing(Nil)) derives Parser.Command
// run -i ubuntu -- echo --not-an-option   →   Run("ubuntu", Trailing(List("echo", "--not-an-option")))
```

`Trailing(Nil)` when no `--` is given; an `Option[Trailing]` field distinguishes an
absent `--` (`None`) from a present-but-empty one (`Some(Trailing(Nil))`). Any type
can opt into the shape via `Parser.trailing`, whose combining function may fail —
e.g. to require a command after `--`. One trailing field per command; in help it
appears as `[-- <args>]` in the usage line. See also
[the meaning of `--`](#the-meaning-of---) above.

Fine-tune with annotations:

| Annotation | Effect |
|---|---|
| `@name("out")` | override the long name (or command / program name on types); repeatable — later occurrences are aliases, on fields and on enum cases |
| `@short('o')` | add a short alias |
| `@help("...")` | help text for fields, cases, and top-level types |
| `@positional` | positional argument instead of named option |
| `@hidden` | omit from help (still parses); on an enum case, an unlisted command. `--help-all` shows them |
| `@group("Network")` | put the option under a titled help section; on a spliced group field, titles the whole group |
| `@version` | on the top-level type: help header line plus a `--version` flag; requires a `given Versioned[A]` supplying the version string (constant or computed) |
| `@default` | on one command-group case: the default command, run when no command token is given (remaining arguments are forwarded to it) |

On a spliced group field, `@name("net")` prefixes the group's option names
(`--net-host`) and drops their short aliases, so the same group can be spliced more
than once.

Errors accumulate: unknown options, invalid values, missing option values, and
missing required arguments are all collected and reported in one failure, one per
line.

The grammar is validated at compile time: a field type without a `Parser` given,
conflicting or ineffective annotations (`@positional` with `@short`, `@short` on a
subcommand field, ...), shape conflicts (`Option` of a repeated parser, a bare flag
in `Option`, ...), cross-field rules (two subcommand or
trailing fields, positionals mixed with subcommands, a positional after a repeated
one), and duplicate constant names (`@short`/`@name`) are all compile errors. Every
field's instance must carry its shape in its type: the shape *is* the subtype
(`Parser.Value[X]`, `Parser.Flag[X]`, `Parser.Repeated[X]`, ...), which all built-in
instances, the `Parser.of`/`flag`/`repeated`/`trailing` constructors, and the
derivation clauses produce; a given ascribed to plain `Parser[X]` is rejected.
What remains at parser construction is the inherently value-level residue:
label-derived (kebab-cased) name collisions, splice-content rules, and
required-before-optional positional ordering, reported as a descriptive
`IllegalArgumentException` before any arguments are parsed.

## Subcommands

Model commands as an enum and derive the parser from it. Parameterless cases are plain
commands; parameterized cases carry their own options; an enum-typed field nests
another level:

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

You handle a plain enum value; flagged handles the routing — and every level answers
`--help`:

```console
$ gitto remote --help
Manage remotes

Usage: gitto remote [options] <command>

Commands:
  add     Add a remote named <name> for the repository at <url>
  remove  Remove the remote named <name>

Options:
  -h, --help  Show this message and exit

Run 'gitto remote <command> --help' for more information on a command.
```

Things to know:

- Derivation is compositional and stops at enum boundaries: each enum in the command
  tree derives its own `Parser` (note `derives Parser.CommandGroup` on `RemoteAction` above), and
  the parent embeds that instance. Forgetting one is a compile error that says which
  enum needs it. This also means any level can be supplied or customized
  independently — a hand-built `Parser` given for a nested enum is used as-is.
- Put parent options before the subcommand name (`gitto -v clone ...`), as in git.
- An `Option[E]`-typed command field makes the command optional; a field default
  (`action: Action = Action.List`) works too.
- An enum field is commands or a value depending on which instance its type provides:
  `derives Parser.CommandGroup` → subcommands, `derives Parser.Enumerated`
  (parameterless enums only) → a value matched by case name (`--color red`).

## Scripts and other entry points

`parseOrExit` takes any `Seq[String]`, so it works wherever your arguments come from —
a `@main` varargs parameter, a scala-cli script's `args`, or a classic `main`:

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

On `--help` it prints the help screen and exits 0; on a bad command line it prints an
error with a hint to stderr and exits 2.

## Handling results yourself

If you'd rather not exit — in tests, or when embedding a CLI in a bigger program —
`Flagged.parse` returns a value instead. Results use `Result[T, E]` from
[lampepfl/steps](https://github.com/lampepfl/steps): you get `Ok(config)` on success,
and `Err` carrying a `ParseError` that is either the rendered help screen or a failure
message with its hint:

```scala
Flagged.parse[Greet](Seq("--name", "Jamie")) match
  case Ok(cfg)                            => run(cfg)
  case Err(ParseError.Help(text))         => println(text)
  case Err(ParseError.Failure(msg, hint)) => logger.error(msg)
```

`import flagged.*` brings `Result`, `Ok`, and `Err` into scope, and the full steps toolkit
(`map`, `getOrElse`, `toEither`, direct-style `Result:` blocks) is available on parse
results. `Flagged.help[Greet]` gives you the help text without parsing anything.

## Parsing your own value types

Option and positional values use the same `Parser[A]` typeclass as commands, in its
value shapes. Instances for
`String`, `Char`, `Boolean`, the numeric types, `BigInt`/`BigDecimal`,
`java.nio.file.Path`, `java.io.File`, `UUID`, the common `java.time` types, and
`FiniteDuration` (`"30s"`, `"5.minutes"`) are built in. Instances follow platform
availability: `java.time` types are JVM-only, `Path` is JVM and Scala Native.

A custom value parser is a one-liner, and its type name becomes the `<metavar>` in help
output:

```scala
given Parser.Value[Port] = Parser.of[Port]("port")(s =>
  s.toIntOption.filter(p => p > 0 && p < 65536) match
    case Some(p) => Ok(Port(p))
    case None    => Err(s"'$s' is not a valid port"))
```

A parser's *shape* is its subtype — `Parser.Value`, `Parser.Flag`,
`Parser.Repeated`, `Parser.Trailing`, `Parser.Command`/`CommandGroup`. `Parser.of`
builds single-value parsers; `Parser.repeated` builds parsers whose argument may
appear any number of times, each occurrence parsed by a `Parser.Value` element (so
repeats cannot nest, by construction) and the collected elements combined by a
function of your choice, from an `IndexedSeq` view of the collected elements. The
provided `List`/`Seq`/`Vector`/`Map` instances are ordinary `Parser.repeated`
definitions, any other collection with a `scala.collection.Factory` works out of
the box (`Set`, `ArraySeq`, sorted collections, ...), and any type can opt in the
same way — including with constraints,
since the combining function may fail (it is also invoked empty when the argument
is absent):

```scala
given Parser.Repeated[Set[String]] = Parser.repeated[String, Set[String]](l => Ok(l.toSet))

given Parser.Repeated[NonEmpty] = Parser.repeated[Int, NonEmpty](l =>
  if l.isEmpty then Err("expected at least one occurrence") else Ok(NonEmpty(l.toList)))
```

Flag shape is pluggable the same way: `Parser.flag` builds the value from the number
of occurrences (with an optional parser for the explicit `--flag=value` form).
`Boolean`'s parser is defined exactly like this, the bundled `Count` type gives
counting flags (`-vvv` → `Count(3)`), and `fromCount` may fail, so occurrence bounds
are expressible:

```scala
given Parser.Flag[Verbosity] = Parser.flag(n =>
  if n <= 3 then Ok(Verbosity(n)) else Err(s"at most 3 occurrences (got $n)"))
```

## Sharing option groups

A field whose type is a *case class* with a `Parser` splices that group's options
directly into the surrounding command — the flattened options parse as if declared
inline, and the group is reconstructed as a value:

```scala
case class LogOpts(@short('q') quiet: Boolean = false, logLevel: String = "info") derives Parser.Command

case class Serve(port: Int = 8080, logging: LogOpts = LogOpts()) derives Parser.Command
// serve --port 9000 -q --log-level debug
```

Groups nest, and work inside subcommand cases, so common options can be shared across
a whole command tree. A name collision between a command and a spliced group (or two
groups) is reported at parser construction; use `@name`/`@short` on either side to
disambiguate. Spliced groups cannot contain positional fields and cannot be `Option`al.

Enums with parameterless cases can derive a by-name value parser:

```scala
enum LogLevel derives Parser.Enumerated:
  case Debug, Info, Warn, Error
```

gives you `--level warn` (kebab-cased, matched exactly) and the metavar
`<debug|info|warn|error>`.
