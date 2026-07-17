package flagged

import java.io.File
import java.nio.file.{InvalidPathException, Path, Paths}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.time.format.DateTimeParseException
import java.util.UUID
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.deriving.Mirror
import flagged.internal.{Assemble, Engine, HelpFmt}

/** A counting flag: `-vvv` parses as `Count(3)`, absent as `Count(0)`. */
final case class Count(value: Int)

object Count:
  given Parser.Aux[Count, Parser.Shape.Flag] = Parser.flag(n => Ok(Count(n)))

/** The raw arguments after `--`, collected verbatim (no option parsing). Empty when no `--` is
  * given; use `Option[Trailing]` to distinguish an absent `--` from a present-but-empty one.
  */
final case class Trailing(args: List[String])

object Trailing:
  given Parser.Aux[Trailing, Parser.Shape.Trailing] = Parser.trailing(l => Ok(Trailing(l)))

/** Describes how command-line input becomes an `A`. The underlying [[Parser.Schema]] encodes the
  * *shape*:
  *
  *   - `Value` — one token per occurrence (numbers, paths, enum-by-name values, ...)
  *   - `Flag` — no token; built from the occurrence count (booleans, counters)
  *   - `Repeated` — any number of occurrences, combined from an element parser
  *   - `Trailing` — the raw arguments after `--`, taken verbatim
  *   - `Command` — a full command grammar: options, positionals, subcommands
  *
  * The same type is used at every level: a field whose parser is command-shaped becomes nested
  * subcommands (sums) or a spliced options group (products); any other shape parses as an option or
  * positional value. Every parser is also runnable: `parse`/`parseOrExit` interpret a value-shaped
  * parser as a single-argument command line.
  */
@scala.annotation.implicitNotFound(
  "No given Parser[${A}] found.\n" +
    "For a subcommand enum add `derives Parser.CommandGroup`; for a spliceable options group add `derives Parser.Command`;\n" +
    "for an enum parsed by case name, add `derives Parser.Enumerated`;\n" +
    "for other value types provide one with Parser.of / Parser.flag / Parser.repeated."
)
sealed trait Parser[A]:
  self =>
  def schema: Parser.Schema[A]

  /** The parser's shape at the type level. Library constructors, given instances, and the
    * derivation witnesses (`Parser.Command`, `Parser.Enumerated`) refine this member (see
    * [[Parser.Aux]]), letting derivation reject invalid shape/annotation combinations at compile
    * time. Instances that reach a use site unrefined — a given explicitly ascribed to plain
    * `Parser[A]` — are validated when the command is constructed instead.
    */
  type ShapeT <: Parser.Shape

  /** The help metavar for value shapes; the program name for command shapes. */
  final def typeName: String = schema match
    case Parser.Schema.Value(name, _)       => name
    case Parser.Schema.Flag(_, _)           => "flag"
    case Parser.Schema.Repeated(element, _) => element.typeName
    case Parser.Schema.Trailing(_)          => "args"
    case Parser.Schema.Command(_, prog)     => prog

  /** Parse a single token (for repeated parsers: one element, then combine; for flags: the explicit
    * `--flag=value` form, if supported).
    */
  final def read(s: String): Result[A, String] = schema match
    case Parser.Schema.Value(_, parse)    => parse(s)
    case Parser.Schema.Flag(_, fromValue) =>
      fromValue.fold(Err(s"'$s': this flag does not take a value"))(f => f(s))
    case Parser.Schema.Repeated(element, build) => element.read(s).flatMap(e => build(List(e)))
    case Parser.Schema.Trailing(build)          => build(List(s))
    case Parser.Schema.Command(_, prog)         =>
      Err(s"'$s': '$prog' is a command parser, not a single value")

  final def map[B](f: A => B): Parser.Aux[B, ShapeT] = emap(a => Ok(f(a)))

  /** Validate/transform the parsed value. On command shapes this composes after the command is
    * built — parse-time cross-field validation.
    */
  final def emap[B](f: A => Result[B, String]): Parser.Aux[B, ShapeT] = Parser.mk[B, ShapeT]:
    schema match
      case Parser.Schema.Value(name, parse) => Parser.Schema.Value(name, s => parse(s).flatMap(f))
      case Parser.Schema.Flag(fromCount, fromValue) =>
        Parser.Schema.Flag(n => fromCount(n).flatMap(f), fromValue.map(g => s => g(s).flatMap(f)))
      case Parser.Schema.Repeated(element, build) =>
        Parser.Schema.Repeated(element, l => build(l).flatMap(f))
      case Parser.Schema.Trailing(build) =>
        Parser.Schema.Trailing(l => build(l).flatMap(f))
      case Parser.Schema.Command(impl, prog) =>
        Parser.Schema.Command(
          impl.copy(build = arr => impl.build(arr).flatMap(a => f(a.asInstanceOf[A]))),
          prog
        )

  /** Rename the metavar (value shapes) or the default program name (command shapes). */
  final def withTypeName(name: String): Parser.Aux[A, ShapeT] = Parser.mk[A, ShapeT]:
    schema match
      case Parser.Schema.Value(_, parse)          => Parser.Schema.Value(name, parse)
      case flag: Parser.Schema.Flag[A]            => flag
      case Parser.Schema.Repeated(element, build) =>
        Parser.Schema.Repeated(element.withTypeName(name), build)
      case trailing: Parser.Schema.Trailing[A] => trailing
      case Parser.Schema.Command(impl, _)      => Parser.Schema.Command(impl, name)

  /** The command grammar: command shapes directly; value shapes as a command line with one
    * positional argument.
    */
  private[flagged] final def command: internal.Command = schema match
    case Parser.Schema.Command(impl, _) => impl
    case _                              => Assemble.singleValueCommand(this)

  /** Parse `args`, reporting help/errors as values on the `Err` channel. */
  final def parse(args: Seq[String]): ParseResult[A] = parse(args, typeName)

  final def parse(args: Seq[String], prog: String): ParseResult[A] =
    Engine.run(command, prog, Nil, args.toList).asInstanceOf[ParseResult[A]]

  /** Parse `args`; on `--help` print the help screen and exit 0, on error print a message to stderr
    * and exit 2. Intended for `@main` methods and scripts.
    */
  final def parseOrExit(args: Seq[String]): A = parseOrExit(args, typeName)

  final def parseOrExit(args: Seq[String], prog: String): A =
    parse(args, prog) match
      case Ok(a)                      => a
      case Err(ParseError.Help(text)) =>
        println(text)
        sys.exit(0)
      case Err(ParseError.Failure(message, hint)) =>
        System.err.println(s"$prog: $message")
        if hint.nonEmpty then System.err.println(hint)
        sys.exit(2)

  /** The rendered top-level help screen. */
  final def help: String = help(typeName)

  final def help(prog: String): String = HelpFmt.render(command, prog, Nil)

object Parser:
  def apply[A](using p: Parser[A]): Parser[A] = p

  /** Type-level counterpart of [[Schema]]'s cases, carried by [[Parser.Aux]]. */
  sealed trait Shape
  object Shape:
    sealed trait Value extends Shape
    sealed trait Flag  extends Shape

    /** A flag that also accepts the explicit `--flag=value` form (and is therefore usable
      * positionally and inside `Option`).
      */
    sealed trait ValuedFlag extends Flag
    sealed trait Repeated   extends Shape
    sealed trait Trailing   extends Shape

    /** A single command's grammar (a product: options, positionals, trailing). */
    sealed trait Command extends Shape

    /** A group of subcommands (a sum); usable wherever a command is. */
    sealed trait CommandGroup extends Command

  /** A parser whose shape is visible in its type. */
  type Aux[A, S <: Shape] = Parser[A] { type ShapeT = S }

  private[flagged] def mk[A, S <: Shape](s: Schema[A]): Aux[A, S] = new Parser[A]:
    type ShapeT = S
    def schema = s

  /** The shape of a parser. */
  enum Schema[A]:
    /** One token per occurrence. */
    case Value[T](typeName: String, parse: String => Result[T, String]) extends Schema[T]

    /** A flag: takes no token; the value is built from the number of occurrences (`-vvv` → 3,
      * absent → 0). `fromValue` optionally supports the explicit `--flag=value` form.
      */
    case Flag[T](
        fromCount: Int => Result[T, String],
        fromValue: Option[String => Result[T, String]]
    ) extends Schema[T]

    /** Any number of occurrences, each parsed by `element`, combined with `build` (also invoked
      * with `Nil` when the argument is absent — return `Err` to require at least one occurrence).
      */
    case Repeated[E, T](element: Parser[E], build: List[E] => Result[T, String]) extends Schema[T]

    /** The raw arguments after `--`, taken verbatim; `build` combines them (also invoked with `Nil`
      * when no `--` is given — return `Err` to require one).
      */
    case Trailing[T](build: List[String] => Result[T, String]) extends Schema[T]

    /** A full command grammar: named options, positionals, subcommands, splices. */
    case Command[T](impl: flagged.internal.Command, prog: String) extends Schema[T]

  /** Build a single-value parser from a name and a parse function. */
  def of[A](name: String)(f: String => Result[A, String]): Aux[A, Shape.Value] =
    mk(Schema.Value(name, f))

  /** Opt `A` into flag shape: the field takes no value and is built from the number of occurrences
    * on the command line.
    */
  def flag[A](fromCount: Int => Result[A, String]): Aux[A, Shape.Flag] =
    mk(Schema.Flag(fromCount, None))

  /** A flag that additionally accepts the explicit `--flag=value` form (which also makes it usable
    * positionally and inside `Option`).
    */
  def flag[A](
      fromCount: Int => Result[A, String],
      fromValue: String => Result[A, String]
  ): Aux[A, Shape.ValuedFlag] =
    mk(Schema.Flag(fromCount, Some(fromValue)))

  /** Opt `A` into repeated shape: each occurrence is parsed with the element's parser, and the
    * collected elements are combined with `build`.
    */
  def repeated[E, A](build: List[E] => Result[A, String])(
      using element: Parser[E]
  ): Aux[A, Shape.Repeated] =
    mk(Schema.Repeated(element, build))

  /** Opt `A` into trailing shape: it is built from the raw arguments after `--`. */
  def trailing[A](build: List[String] => Result[A, String]): Aux[A, Shape.Trailing] =
    mk(Schema.Trailing(build))

  /** Called by derivation. Not intended for direct use. */
  def make[A](cmd: flagged.internal.Command, prog: String): Aux[A, Shape.Command] =
    mk(Schema.Command(cmd, prog))

  /** Called by derivation for sums. Not intended for direct use. */
  def makeGroup[A](cmd: flagged.internal.Command, prog: String): Aux[A, Shape.CommandGroup] =
    mk(Schema.Command(cmd, prog))

  /** Derivation witness for a single command: `case class Config(...) derives Parser.Command`. As a
    * field of another command, a `Command`-shaped parser is a spliced options group.
    */
  final class Command[A](val parser: Parser.Aux[A, Shape.Command])
  object Command:
    inline def derived[A](using m: Mirror.ProductOf[A]): Command[A] =
      new Command[A](internal.Derive.product[A])

  /** Derivation witness for a group of subcommands: `enum Cmd derives Parser.CommandGroup`. As a
    * field of another command, a `CommandGroup`-shaped parser is a set of nested subcommands.
    *
    * Derivation is `Mirror`-based and compositional: fields use the `Parser` given for their type,
    * dispatched on its statically known shape.
    */
  final class CommandGroup[A](val parser: Parser.Aux[A, Shape.CommandGroup])
  object CommandGroup:
    inline def derived[A](using m: Mirror.SumOf[A]): CommandGroup[A] =
      new CommandGroup[A](internal.Derive.sum[A])

  /** A parser for an enum whose cases are all parameterless, matched by kebab-cased case name,
    * case-insensitively. Usable directly or via `derives Parser.Enumerated`.
    */
  inline def enumerated[A](using Mirror.SumOf[A]): Aux[A, Shape.Value] =
    internal.Derive.enumParser[A]

  /** Derivation-flavor witness enabling `enum Color derives Parser.Enumerated`: the enum's parser
    * parses *values* by case name instead of becoming subcommands.
    */
  final class Enumerated[A](val parser: Parser.Aux[A, Parser.Shape.Value])

  object Enumerated:
    inline def derived[A](using Mirror.SumOf[A]): Parser.Enumerated[A] =
      Parser.Enumerated(enumerated[A])

  given fromEnum[A](using e: Parser.Enumerated[A]): Aux[A, Shape.Value]   = e.parser
  given fromCommand[A](using c: Parser.Command[A]): Aux[A, Shape.Command] = c.parser

  given fromCommandGroup[A](using g: Parser.CommandGroup[A]): Aux[A, Shape.CommandGroup] = g.parser

  given [A](using Parser[A]): Aux[List[A], Shape.Repeated]   = repeated[A, List[A]](l => Ok(l))
  given [A](using Parser[A]): Aux[Vector[A], Shape.Repeated] =
    repeated[A, Vector[A]](l => Ok(l.toVector))
  given [A](using Parser[A]): Aux[Seq[A], Shape.Repeated] = repeated[A, Seq[A]](l => Ok(l))

  private def numeric[A](name: String)(f: String => A): Aux[A, Shape.Value] =
    of(name)(s =>
      try Ok(f(s.trim))
      catch case _: NumberFormatException => Err(s"'$s' is not a valid $name")
    )

  given Aux[String, Shape.Value]     = of("string")(Ok(_))
  given Aux[Int, Shape.Value]        = numeric("int")(_.toInt)
  given Aux[Long, Shape.Value]       = numeric("long")(_.toLong)
  given Aux[Short, Shape.Value]      = numeric("short")(_.toShort)
  given Aux[Byte, Shape.Value]       = numeric("byte")(_.toByte)
  given Aux[Float, Shape.Value]      = numeric("float")(_.toFloat)
  given Aux[Double, Shape.Value]     = numeric("double")(_.toDouble)
  given Aux[BigInt, Shape.Value]     = numeric("integer")(BigInt(_))
  given Aux[BigDecimal, Shape.Value] = numeric("decimal")(BigDecimal(_))

  given Aux[Boolean, Shape.ValuedFlag] = flag(n => Ok(n > 0), internal.Runtime.parseBool)

  given Aux[Char, Shape.Value] = of("char")(s =>
    if s.length == 1 then Ok(s.charAt(0)) else Err(s"'$s' is not a single character")
  )

  given Aux[Path, Shape.Value] = of("path")(s =>
    try Ok(Paths.get(s))
    catch case _: InvalidPathException => Err(s"'$s' is not a valid path")
  )

  given Aux[File, Shape.Value] = of("file")(s => Ok(new File(s)))

  given Aux[UUID, Shape.Value] = of("uuid")(s =>
    try Ok(UUID.fromString(s.trim))
    catch case _: IllegalArgumentException => Err(s"'$s' is not a valid UUID")
  )

  private def temporal[A](name: String)(f: String => A): Aux[A, Shape.Value] =
    of(name)(s =>
      try Ok(f(s.trim))
      catch case _: DateTimeParseException => Err(s"'$s' is not a valid $name")
    )

  given Aux[LocalDate, Shape.Value]     = temporal("date")(LocalDate.parse)
  given Aux[LocalTime, Shape.Value]     = temporal("time")(LocalTime.parse)
  given Aux[LocalDateTime, Shape.Value] = temporal("date-time")(LocalDateTime.parse)
  given Aux[Instant, Shape.Value]       = temporal("instant")(Instant.parse)

  given Aux[FiniteDuration, Shape.Value] = of("duration")(s =>
    try
      Duration(s.trim) match
        case fd: FiniteDuration => Ok(fd)
        case _                  => Err(s"'$s' is not a finite duration")
    catch
      case _: NumberFormatException =>
        Err(s"'$s' is not a valid duration (try e.g. '30s' or '5.minutes')")
  )

/** Convenience entry points:
  *
  * {{{
  * @main def app(args: String*): Unit =
  *   val cfg = Flagged.parseOrExit[Config](args)
  * }}}
  */
object Flagged:
  def parse[A](args: Seq[String])(using p: Parser[A]): ParseResult[A]               = p.parse(args)
  def parse[A](args: Seq[String], prog: String)(using p: Parser[A]): ParseResult[A] =
    p.parse(args, prog)
  def parseOrExit[A](args: Seq[String])(using p: Parser[A]): A               = p.parseOrExit(args)
  def parseOrExit[A](args: Seq[String], prog: String)(using p: Parser[A]): A =
    p.parseOrExit(args, prog)
  def help[A](using p: Parser[A]): String               = p.help
  def help[A](prog: String)(using p: Parser[A]): String = p.help(prog)
