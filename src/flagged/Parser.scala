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
  given Parser.Flag[Count] = Parser.flag(n => Ok(Count(n)))

/** The raw arguments after `--`, collected verbatim (no option parsing). Empty when no `--` is
  * given; use `Option[Trailing]` to distinguish an absent `--` from a present-but-empty one.
  */
final case class Trailing(args: List[String])

object Trailing:
  given Parser.Trailing[Trailing] = Parser.trailing(l => Ok(Trailing(l)))

/** Describes how command-line input becomes an `A`. The *shape* is the subtype:
  *
  *   - [[Parser.Value]] — one token per occurrence (numbers, paths, enum values, ...)
  *   - [[Parser.Flag]] — no token; built from the occurrence count ([[Parser.ValuedFlag]]
  *     additionally accepts the explicit `--flag=value` form)
  *   - [[Parser.Repeated]] — any number of occurrences of a `Value` element, combined
  *   - [[Parser.Trailing]] — the raw arguments after `--`, taken verbatim
  *   - [[Parser.Command]] — a single command's grammar; as a field, a spliced options group
  *     ([[Parser.CommandGroup]] is a sum: nested subcommands)
  *
  * The same type is used at every level, and field semantics follow the instance's subtype, which
  * derivation requires to be statically known. Every parser is also runnable: `parse`/`parseOrExit`
  * interpret value shapes as a single-argument command line.
  */
@scala.annotation.implicitNotFound(
  "No given Parser[${A}] found.\n" +
    "For a subcommand enum add `derives Parser.CommandGroup`; for a spliceable options group add `derives Parser.Command`;\n" +
    "for an enum parsed by case name, add `derives Parser.Enumerated`;\n" +
    "for other value types provide one with Parser.of / Parser.flag / Parser.repeated / Parser.trailing."
)
sealed trait Parser[A]:

  /** The help metavar for value shapes; the program name for command shapes. */
  def typeName: String

  /** Validate/transform the parsed value; each shape returns its own shape. On command shapes this
    * composes after the command is built — parse-time cross-field validation.
    */
  def emap[B](f: A => Result[B, String]): Parser[B]

  final def map[B](f: A => B): Parser[B] = emap(a => Ok(f(a)))

  /** Parse a single token (for repeated parsers: one element, then combine; for valued flags: the
    * explicit `--flag=value` form).
    */
  final def read(s: String): Result[A, String] = (this: @unchecked) match
    case v: Parser.Value[A]       => v.parse(s)
    case vf: Parser.ValuedFlag[A] => vf.fromValue(s)
    case _: Parser.Flag[A]        => Err(s"'$s': this flag does not take a value")
    case r: Parser.Repeated[A]    => r.element.parse(s).flatMap(e => r.build(List(e)))
    case t: Parser.Trailing[A]    => t.build(List(s))
    case c: Parser.Command[A]     =>
      Err(s"'$s': '${c.prog}' is a command parser, not a single value")

  /** The command grammar: command shapes directly; value shapes as a command line with one
    * positional argument.
    */
  private[flagged] final def command: internal.Command = this match
    case c: Parser.Command[?] => c.impl
    case _                    => Assemble.singleValueCommand(this)

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

  /** One token per occurrence. */
  sealed trait Value[A] extends Parser[A]:
    def parse: String => Result[A, String]
    def emap[B](f: A => Result[B, String]): Value[B] = of(typeName)(s => parse(s).flatMap(f))
    def withTypeName(name: String): Value[A]         = of(name)(parse)

  /** A value parser for an enum with parameterless cases, matched by kebab-cased case name,
    * case-insensitively: `enum Color derives Parser.Enumerated`.
    */
  sealed trait Enumerated[A] extends Value[A]
  object Enumerated:
    inline def derived[A](using Mirror.SumOf[A]): Enumerated[A] = internal.Derive.enumParser[A]

  /** A flag: takes no token; the value is built from the number of occurrences (`-vvv` → 3, absent
    * → 0).
    */
  sealed trait Flag[A] extends Parser[A]:
    def fromCount: Int => Result[A, String]
    final def typeName: String                      = "flag"
    def emap[B](f: A => Result[B, String]): Flag[B] = flag(n => fromCount(n).flatMap(f))

  /** A flag that additionally accepts the explicit `--flag=value` form (and is therefore usable
    * positionally and inside `Option`).
    */
  sealed trait ValuedFlag[A] extends Flag[A]:
    def fromValue: String => Result[A, String]
    override def emap[B](f: A => Result[B, String]): ValuedFlag[B] =
      flag(n => fromCount(n).flatMap(f), s => fromValue(s).flatMap(f))

  /** Any number of occurrences, each parsed by a `Value` element (so repeats cannot nest, by
    * construction), combined with `build` — which is also invoked with `Nil` when the argument is
    * absent; return `Err` to require at least one occurrence.
    */
  sealed trait Repeated[A] extends Parser[A]:
    type Elem
    def element: Value[Elem]
    def build: List[Elem] => Result[A, String]
    final def typeName: String                          = element.typeName
    def emap[B](f: A => Result[B, String]): Repeated[B] =
      repeated[Elem, B](l => build(l).flatMap(f))(using element)

  /** The raw arguments after `--`, taken verbatim; `build` combines them (also invoked with `Nil`
    * when no `--` is given — return `Err` to require one).
    */
  sealed trait Trailing[A] extends Parser[A]:
    def build: List[String] => Result[A, String]
    final def typeName: String                          = "args"
    def emap[B](f: A => Result[B, String]): Trailing[B] = trailing(l => build(l).flatMap(f))

  /** A single command's grammar: named options, positionals, trailing, splices. As a field of
    * another command, its options are spliced in.
    */
  sealed trait Command[A] extends Parser[A]:
    private[flagged] def impl: flagged.internal.Command
    def prog: String
    final def typeName: String                         = prog
    def emap[B](f: A => Result[B, String]): Command[B] = make(emapImpl(f), prog)
    def withProg(name: String): Command[A]             = make(impl, name)
    private[flagged] final def emapImpl[B](f: A => Result[B, String]): flagged.internal.Command =
      impl.copy(build = arr => impl.build(arr).flatMap(a => f(a.asInstanceOf[A])))
  object Command:
    /** Derivation for a single command: `case class Config(...) derives Parser.Command`. */
    inline def derived[A](using m: Mirror.ProductOf[A]): Command[A] = internal.Derive.product[A]

  /** A group of subcommands (a sum), nested wherever it appears as a field. */
  sealed trait CommandGroup[A] extends Command[A]:
    override def emap[B](f: A => Result[B, String]): CommandGroup[B] = makeGroup(emapImpl(f), prog)
    override def withProg(name: String): CommandGroup[A]             = makeGroup(impl, name)
  object CommandGroup:
    /** Derivation for subcommands: `enum Cmd derives Parser.CommandGroup`. */
    inline def derived[A](using m: Mirror.SumOf[A]): CommandGroup[A] = internal.Derive.sum[A]

  // ---- constructors ----------------------------------------------------------

  /** Build a single-value parser from a name and a parse function. */
  def of[A](name: String)(f: String => Result[A, String]): Value[A] = new Value[A]:
    def typeName = name
    def parse    = f

  private[flagged] def enumeratedOf[A](name: String)(
      f: String => Result[A, String]
  ): Enumerated[A] =
    new Enumerated[A]:
      def typeName = name
      def parse    = f

  /** Opt `A` into flag shape: the field takes no value and is built from the number of occurrences
    * on the command line.
    */
  def flag[A](count: Int => Result[A, String]): Flag[A] = new Flag[A]:
    def fromCount = count

  /** A flag that additionally accepts the explicit `--flag=value` form. */
  def flag[A](count: Int => Result[A, String], value: String => Result[A, String]): ValuedFlag[A] =
    new ValuedFlag[A]:
      def fromCount = count
      def fromValue = value

  /** Opt `A` into repeated shape: each occurrence is parsed with the element's (single-value)
    * parser, and the collected elements are combined with `combine`.
    */
  def repeated[E, A](combine: List[E] => Result[A, String])(using elem: Value[E]): Repeated[A] =
    new Repeated[A]:
      type Elem = E
      def element = elem
      def build   = combine

  /** Opt `A` into trailing shape: it is built from the raw arguments after `--`. */
  def trailing[A](combine: List[String] => Result[A, String]): Trailing[A] = new Trailing[A]:
    def build = combine

  /** Called by derivation. Not intended for direct use. */
  def make[A](cmd: flagged.internal.Command, name: String): Command[A] = new Command[A]:
    private[flagged] def impl = cmd
    def prog                  = name

  /** Called by derivation for sums. Not intended for direct use. */
  def makeGroup[A](cmd: flagged.internal.Command, name: String): CommandGroup[A] =
    new CommandGroup[A]:
      private[flagged] def impl = cmd
      def prog                  = name

  // ---- instances --------------------------------------------------------------

  given [A](using Value[A]): Repeated[List[A]]   = repeated[A, List[A]](l => Ok(l))
  given [A](using Value[A]): Repeated[Vector[A]] = repeated[A, Vector[A]](l => Ok(l.toVector))
  given [A](using Value[A]): Repeated[Seq[A]]    = repeated[A, Seq[A]](l => Ok(l))

  private def numeric[A](name: String)(f: String => A): Value[A] =
    of(name)(s =>
      try Ok(f(s.trim))
      catch case _: NumberFormatException => Err(s"'$s' is not a valid $name")
    )

  given Value[String]     = of("string")(Ok(_))
  given Value[Int]        = numeric("int")(_.toInt)
  given Value[Long]       = numeric("long")(_.toLong)
  given Value[Short]      = numeric("short")(_.toShort)
  given Value[Byte]       = numeric("byte")(_.toByte)
  given Value[Float]      = numeric("float")(_.toFloat)
  given Value[Double]     = numeric("double")(_.toDouble)
  given Value[BigInt]     = numeric("integer")(BigInt(_))
  given Value[BigDecimal] = numeric("decimal")(BigDecimal(_))

  given ValuedFlag[Boolean] = flag(n => Ok(n > 0), internal.Runtime.parseBool)

  given Value[Char] = of("char")(s =>
    if s.length == 1 then Ok(s.charAt(0)) else Err(s"'$s' is not a single character")
  )

  given Value[Path] = of("path")(s =>
    try Ok(Paths.get(s))
    catch case _: InvalidPathException => Err(s"'$s' is not a valid path")
  )

  given Value[File] = of("file")(s => Ok(new File(s)))

  given Value[UUID] = of("uuid")(s =>
    try Ok(UUID.fromString(s.trim))
    catch case _: IllegalArgumentException => Err(s"'$s' is not a valid UUID")
  )

  private def temporal[A](name: String)(f: String => A): Value[A] =
    of(name)(s =>
      try Ok(f(s.trim))
      catch case _: DateTimeParseException => Err(s"'$s' is not a valid $name")
    )

  given Value[LocalDate]     = temporal("date")(LocalDate.parse)
  given Value[LocalTime]     = temporal("time")(LocalTime.parse)
  given Value[LocalDateTime] = temporal("date-time")(LocalDateTime.parse)
  given Value[Instant]       = temporal("instant")(Instant.parse)

  given Value[FiniteDuration] = of("duration")(s =>
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
