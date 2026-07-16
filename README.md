# claw

Command-line argument parsing for Scala 3. You describe your CLI as plain data — a
case class for options, an enum for subcommands — and claw derives the parser, the
`--help` screens, and the error messages at compile time.

```scala
import claw.*

@help("Greet someone from the command line")
case class Greet(
    @short('n') @help("Who to greet") name: String = "world",
    @short('e') @help("Add excitement") excited: Boolean = false,
    @short('r') @help("How many times to greet") repeat: Int = 1
) derives Parser

@main def greet(args: String*): Unit =
  val cfg = Claw.parseOrExit[Greet](args)
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
`-rn 3`); `--` to end option parsing; `-` and negative numbers are treated as values.

## Getting claw

claw is not yet published to a Maven repository. To try it out, clone this repository
and run `scala-cli test .` or any of the demos in `examples/`; to use it in a project,
vendor the `src/claw` directory.

## Declaring options

Each case class field becomes a named option, `--kebab-cased` after the field name.
Semantics are instance-driven: a field whose type has a `Parser` given becomes nested
subcommands or a spliced options group; value shapes parse as option values, with
the parser's *schema* deciding whether the option is a flag, takes one value, or
repeats:

| Field shape | Meaning |
|---|---|
| `x: Boolean` | flag `--x` (also `--x=false`); always optional |
| `x: Count` (or any flag-schema `Parser[A]`) | counting flag: `-vvv` → `Count(3)` |
| `x: A` (value-schema `Parser[A]`) | required option `--x <a>` |
| `x: A = default` | optional, default shown in help |
| `x: Option[A]` | optional, `None` when absent |
| `x: A` (repeated-schema `Parser[A]`, e.g. `List`/`Seq`/`Vector`) | repeatable |
| `x: E` (enum `E derives Parser`) | nested subcommands |
| `x: P` (case class `P derives Parser`) | options group spliced into this command |
| `@positional x: A` | positional argument (same rules) |

Fine-tune with annotations:

| Annotation | Effect |
|---|---|
| `@name("out")` | override the long name (or command / program name on types) |
| `@short('o')` | add a short alias |
| `@help("...")` | help text for fields, cases, and top-level types |
| `@positional` | positional argument instead of named option |

A field type without a `Parser` given is a compile error.
Structural mistakes — duplicate names, a second `-h`, a required positional after an
optional one — are reported with a descriptive `IllegalArgumentException` when the
parser instance is constructed, before any arguments are parsed.

## Subcommands

Model commands as an enum and derive the parser from it. Parameterless cases are plain
commands; parameterized cases carry their own options; an enum-typed field nests
another level:

```scala
@name("gitto")
@help("gitto — a tiny version control tool")
enum Gitto derives Parser:
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

enum RemoteAction derives Parser:
  case Add(@positional name: String, @positional url: String)
  case Remove(@positional name: String)

@main def gitto(args: String*): Unit =
  Claw.parseOrExit[Gitto](args) match
    case Gitto.Clone(repo, dir, depth)             => ???
    case Gitto.Remote(RemoteAction.Add(name, url)) => ???
    case Gitto.Remote(RemoteAction.Remove(name))   => ???
    case Gitto.Status(short)                       => ???
```

You handle a plain enum value; claw handles the routing — and every level answers
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
  tree derives its own `Parser` (note `derives Parser` on `RemoteAction` above), and
  the parent embeds that instance. Forgetting one is a compile error that says which
  enum needs it. This also means any level can be supplied or customized
  independently — a hand-built `Parser` given for a nested enum is used as-is.
- Put parent options before the subcommand name (`gitto -v clone ...`), as in git.
- An `Option[E]`-typed command field makes the command optional; a field default
  (`action: Action = Action.List`) works too.
- An enum field is commands or a value depending on which instance its type provides:
  `derives Parser` → subcommands, `derives Parser.Enumerated` (parameterless enums
  only) → a value matched by case name (`--color red`).

## Scripts and other entry points

`parseOrExit` takes any `Seq[String]`, so it works wherever your arguments come from —
a `@main` varargs parameter, a scala-cli script's `args`, or a classic `main`:

```scala
// backup.sc — run with scala-cli
import claw.*

case class Backup(
    @short('d') @help("Destination directory") dest: String,
    @short('n') @help("Print actions without copying") dryRun: Boolean = false,
    @positional sources: List[String] = Nil
) derives Parser

val cfg = Claw.parseOrExit[Backup](args.toSeq, prog = "backup")
```

On `--help` it prints the help screen and exits 0; on a bad command line it prints an
error with a hint to stderr and exits 2.

## Handling results yourself

If you'd rather not exit — in tests, or when embedding a CLI in a bigger program —
`Claw.parse` returns a value instead. Results use `Result[T, E]` from
[lampepfl/steps](https://github.com/lampepfl/steps): you get `Ok(config)` on success,
and `Err` carrying a `ParseError` that is either the rendered help screen or a failure
message with its hint:

```scala
Claw.parse[Greet](Seq("--name", "Jamie")) match
  case Ok(cfg)                            => run(cfg)
  case Err(ParseError.Help(text))         => println(text)
  case Err(ParseError.Failure(msg, hint)) => logger.error(msg)
```

`import claw.*` brings `Result`, `Ok`, and `Err` into scope, and the full steps toolkit
(`map`, `getOrElse`, `toEither`, direct-style `Result:` blocks) is available on parse
results. `Claw.help[Greet]` gives you the help text without parsing anything.

## Parsing your own value types

Option and positional values use the same `Parser[A]` typeclass as commands, in its
value shapes. Instances for
`String`, `Char`, `Boolean`, the numeric types, `BigInt`/`BigDecimal`,
`java.nio.file.Path`, `java.io.File`, `UUID`, the common `java.time` types, and
`FiniteDuration` (`"30s"`, `"5.minutes"`) are built in.

A custom value parser is a one-liner, and its type name becomes the `<metavar>` in help
output:

```scala
given Parser[Port] = Parser.of[Port]("port")(s =>
  s.toIntOption.filter(p => p > 0 && p < 65536) match
    case Some(p) => Ok(Port(p))
    case None    => Err(s"'$s' is not a valid port"))
```

A parser's underlying `Parser.Schema` encodes its *shape* — `Value`, `Flag`,
`Repeated`, or `Command` (the shape derivation produces for case classes and enums).
`Parser.of` builds single-value parsers; `Parser.repeated` builds parsers whose
argument may appear any number of times, each occurrence parsed by an element parser
and the collected elements combined by a function of your choice. The provided
`List`/`Seq`/`Vector` instances are ordinary `Parser.repeated` definitions, and any
type can opt in the same way — including with constraints, since the combining
function may fail (it also receives `Nil` when the argument is absent):

```scala
given Parser[Set[String]] = Parser.repeated[String, Set[String]](l => Ok(l.toSet))

given Parser[NonEmpty] = Parser.repeated[Int, NonEmpty](l =>
  if l.isEmpty then Err("expected at least one occurrence") else Ok(NonEmpty(l)))
```

Flag shape is pluggable the same way: `Parser.flag` builds the value from the number
of occurrences (with an optional parser for the explicit `--flag=value` form).
`Boolean`'s parser is defined exactly like this, the bundled `Count` type gives
counting flags (`-vvv` → `Count(3)`), and `fromCount` may fail, so occurrence bounds
are expressible:

```scala
given Parser[Verbosity] = Parser.flag(n =>
  if n <= 3 then Ok(Verbosity(n)) else Err(s"at most 3 occurrences (got $n)"))
```

## Sharing option groups

A field whose type is a *case class* with a `Parser` splices that group's options
directly into the surrounding command — the flattened options parse as if declared
inline, and the group is reconstructed as a value:

```scala
case class LogOpts(@short('q') quiet: Boolean = false, logLevel: String = "info") derives Parser

case class Serve(port: Int = 8080, logging: LogOpts = LogOpts()) derives Parser
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

gives you `--level warn` (case-insensitive, kebab-cased) and the metavar
`<debug|info|warn|error>`.
