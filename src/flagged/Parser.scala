package flagged

import java.io.File
import scala.collection.immutable.ArraySeq
import java.util.UUID
import scala.deriving.Mirror
import flagged.internal.{Assemble, Engine, HelpFmt}

/** A counting flag: `-vvv` parses as `Count(3)`, absent as `Count(0)`. */
final case class Count(value: Int)

object Count:
  given Parser.Flag[Count]:
    def fromCount(n: Int) = Ok(Count(n))

/** The raw arguments after `--`, collected verbatim (no option parsing). Empty when no `--` is
  * given; use `Option[Trailing]` to distinguish an absent `--` from a present-but-empty one.
  */
final case class Trailing(args: List[String])

object Trailing:
  given Parser.Trailing[Trailing]:
    def build(l: List[String]) = Ok(Trailing(l))

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
    case r: Parser.Repeated[A]    => r.element.parse(s).flatMap(e => r.build(IndexedSeq(e)))
    case t: Parser.Trailing[A]    => t.build(List(s))
    case c: Parser.Command[A]     =>
      Err(s"'$s': '${c.prog}' is a command parser, not a single value")

  /** Engine protocol mirror of [[read]]: parse into `out(i)` instead of boxing the success. Returns
    * the shared [[Result.done]] on success — the hot path allocates nothing.
    */
  private[flagged] final def readInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
    (this: @unchecked) match
      case v: Parser.Value[A]       => v.parseInto(s, out, i)
      case vf: Parser.ValuedFlag[A] => vf.fromValueInto(s, out, i)
      case other                    => Parser.intoSlot(other.read(s), out, i)

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

  /** Unbox a `Result` into a value slot: success is the shared [[Result.done]] (no allocation), an
    * `Err` passes through by covariance (no re-wrapping).
    */
  private[flagged] def intoSlot[A](
      r: Result[A, String],
      out: Array[Any],
      i: Int
  ): Result[Unit, String] =
    r match
      case Ok(v)     => out(i) = v; Result.done
      case e: Err[?] => e.asInstanceOf[Err[String]]

  /** One token per occurrence. */
  sealed trait Value[A] extends Parser[A]:
    def parse(s: String): Result[A, String]
    def emap[B](f: A => Result[B, String]): Value[B] = of(typeName)(s => parse(s).flatMap(f))
    def withTypeName(name: String): Value[A]         = of(name)(parse)

    /** Engine protocol: parse `s` into `out(i)`; [[Result.done]] on success (built-in instances
      * override with direct bodies — no `Ok` per token).
      */
    private[flagged] def parseInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
      intoSlot(parse(s), out, i)

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
    def fromCount(n: Int): Result[A, String]
    final def typeName: String                      = "flag"
    def emap[B](f: A => Result[B, String]): Flag[B] = flag(n => fromCount(n).flatMap(f))

    /** Engine protocol: build from the mention count into `out(i)`. */
    private[flagged] def countInto(n: Int, out: Array[Any], i: Int): Result[Unit, String] =
      intoSlot(fromCount(n), out, i)

  /** A flag that additionally accepts the explicit `--flag=value` form (and is therefore usable
    * positionally and inside `Option`).
    */
  sealed trait ValuedFlag[A] extends Flag[A]:
    def fromValue(s: String): Result[A, String]

    /** Engine protocol: parse the explicit `--flag=value` form into `out(i)`. */
    private[flagged] def fromValueInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
      intoSlot(fromValue(s), out, i)
    override def emap[B](f: A => Result[B, String]): ValuedFlag[B] =
      flag(n => fromCount(n).flatMap(f), s => fromValue(s).flatMap(f))

  /** Any number of occurrences, each parsed by a `Value` element (so repeats cannot nest, by
    * construction), combined with `build` from an indexed view of the collected elements (an
    * `ArraySeq` over the engine's array — also invoked empty when the argument is absent; return
    * `Err` to require at least one occurrence).
    */
  sealed trait Repeated[A] extends Parser[A]:
    type Elem
    def element: Value[Elem]
    def build(l: IndexedSeq[Elem]): Result[A, String]
    final def typeName: String                          = element.typeName
    def emap[B](f: A => Result[B, String]): Repeated[B] =
      repeated[Elem, B](l => build(l).flatMap(f))(using element)
    private[flagged] final def parseElemInto(
        s: String,
        out: Array[Any],
        i: Int
    ): Result[Unit, String] = element.parseInto(s, out, i)
    private[flagged] final def buildErased(l: IndexedSeq[Any]): Result[A, String] =
      build(l.asInstanceOf[IndexedSeq[Elem]])

  /** The raw arguments after `--`, taken verbatim; `build` combines them (also invoked with `Nil`
    * when no `--` is given — return `Err` to require one).
    */
  sealed trait Trailing[A] extends Parser[A]:
    def build(l: List[String]): Result[A, String]
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
    def typeName         = name
    def parse(s: String) = f(s)

  private[flagged] def enumeratedOf[A](name: String, pairs: Vector[(String, A)]): Enumerated[A] =
    new Enumerated[A]:
      def typeName         = name
      def parse(s: String) =
        val key = s.trim
        var i   = 0
        while i < pairs.length do
          if pairs(i)._1.equalsIgnoreCase(key) then return Ok(pairs(i)._2)
          i += 1
        Err(s"'$s' is not one of: ${pairs.map(_._1).mkString(", ")}")
      override private[flagged] def parseInto(s: String, out: Array[Any], k: Int) =
        val key = s.trim
        var i   = 0
        while i < pairs.length do
          if pairs(i)._1.equalsIgnoreCase(key) then
            out(k) = pairs(i)._2
            return Result.done
          i += 1
        Err(s"'$s' is not one of: ${pairs.map(_._1).mkString(", ")}")

  /** Opt `A` into flag shape: the field takes no value and is built from the number of occurrences
    * on the command line.
    */
  def flag[A](count: Int => Result[A, String]): Flag[A] = new Flag[A]:
    def fromCount(n: Int) = count(n)

  /** A flag that additionally accepts the explicit `--flag=value` form. */
  def flag[A](count: Int => Result[A, String], value: String => Result[A, String]): ValuedFlag[A] =
    new ValuedFlag[A]:
      def fromCount(n: Int)    = count(n)
      def fromValue(s: String) = value(s)

  /** Opt `A` into repeated shape: each occurrence is parsed with the element's (single-value)
    * parser, and the collected elements are combined with `combine`.
    */
  def repeated[E, A](combine: IndexedSeq[E] => Result[A, String])(
      using elem: Value[E]
  ): Repeated[A] =
    new Repeated[A]:
      type Elem = E
      def element                 = elem
      def build(l: IndexedSeq[E]) = combine(l)

  /** Opt `A` into trailing shape: it is built from the raw arguments after `--`. */
  def trailing[A](combine: List[String] => Result[A, String]): Trailing[A] = new Trailing[A]:
    def build(l: List[String]) = combine(l)

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

  given [A] => (elem: Value[A]) => Repeated[List[A]]:
    type Elem = A
    def element                 = elem
    def build(l: IndexedSeq[A]) = Ok(l.toList)

  given [A] => (elem: Value[A]) => Repeated[Vector[A]]:
    type Elem = A
    def element                 = elem
    def build(l: IndexedSeq[A]) = Ok(l.toVector)

  given [A] => (elem: Value[A]) => Repeated[Seq[A]]:
    type Elem = A
    def element                 = elem
    def build(l: IndexedSeq[A]) = Ok(l) // the engine's ArraySeq, zero-copy

  private final class PairValue[K, V](k: Value[K], v: Value[V]) extends Value[(K, V)]:
    def typeName         = s"${k.typeName}=${v.typeName}"
    def parse(s: String) =
      s.indexOf('=') match
        case -1 => Err(s"'$s' is not in $typeName form")
        case i  =>
          for
            key   <- k.parse(s.take(i))
            value <- v.parse(s.drop(i + 1))
          yield (key, value)

  /** Each occurrence is one `key=value` entry, split at the first `=`; later entries win. */
  given [K, V] => (k: Value[K], v: Value[V]) => Repeated[Map[K, V]]:
    type Elem = (K, V)
    val element                    = PairValue(k, v)
    def build(l: IndexedSeq[Elem]) = Ok(l.toMap)

  // built-in instances implement `parse` directly, so the default path is plain virtual dispatch
  // with no closures; only user-constructed parsers (`of`, `emap`, ...) go through a function
  // value. `num` takes its conversion inline, so each numeric parse body is direct code.
  private inline def num[A](s: String, name: String)(inline f: String => A): Result[A, String] =
    try Ok(f(s.trim))
    catch case _: NumberFormatException => Err(s"'$s' is not a valid $name")

  private inline def numInto[A](s: String, name: String, out: Array[Any], i: Int)(
      inline f: String => A
  ): Result[Unit, String] =
    try
      out(i) = f(s.trim)
      Result.done
    catch case _: NumberFormatException => Err(s"'$s' is not a valid $name")

  given Value[String]:
    def typeName                                                                = "string"
    def parse(s: String)                                                        = Ok(s)
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      out(i) = s
      Result.done

  given Value[Int]:
    def typeName         = "int"
    def parse(s: String) = num(s, typeName)(_.toInt)
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      numInto(s, typeName, out, i)(_.toInt)

  given Value[Long]:
    def typeName         = "long"
    def parse(s: String) = num(s, typeName)(_.toLong)
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      numInto(s, typeName, out, i)(_.toLong)

  given Value[Short]:
    def typeName         = "short"
    def parse(s: String) = num(s, typeName)(_.toShort)
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      numInto(s, typeName, out, i)(_.toShort)

  given Value[Byte]:
    def typeName         = "byte"
    def parse(s: String) = num(s, typeName)(_.toByte)
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      numInto(s, typeName, out, i)(_.toByte)

  given Value[Float]:
    def typeName         = "float"
    def parse(s: String) = num(s, typeName)(_.toFloat)
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      numInto(s, typeName, out, i)(_.toFloat)

  given Value[Double]:
    def typeName         = "double"
    def parse(s: String) = num(s, typeName)(_.toDouble)
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      numInto(s, typeName, out, i)(_.toDouble)

  given Value[BigInt]:
    def typeName         = "integer"
    def parse(s: String) = num(s, typeName)(BigInt(_))
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      numInto(s, typeName, out, i)(BigInt(_))

  given Value[BigDecimal]:
    def typeName         = "decimal"
    def parse(s: String) = num(s, typeName)(BigDecimal(_))
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      numInto(s, typeName, out, i)(BigDecimal(_))

  // repetition policy belongs to the flag's count parser: repeating a Boolean flag replaces the
  // previous value (the last mention wins, like value options); counting is opt-in via Count
  given ValuedFlag[Boolean]:
    def fromCount(n: Int)    = Ok(n > 0)
    def fromValue(s: String) = internal.Runtime.parseBool(s)
    override private[flagged] def countInto(n: Int, out: Array[Any], i: Int) =
      out(i) = n > 0
      Result.done
    override private[flagged] def fromValueInto(s: String, out: Array[Any], i: Int) =
      internal.Runtime.parseBoolInto(s, out, i)

  given Value[Char]:
    def typeName         = "char"
    def parse(s: String) =
      if s.length == 1 then Ok(s.charAt(0)) else Err(s"'$s' is not a single character")
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      if s.length == 1 then
        out(i) = s.charAt(0)
        Result.done
      else Err(s"'$s' is not a single character")

  given Value[File]:
    def typeName                                                                = "file"
    def parse(s: String)                                                        = Ok(new File(s))
    override private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      out(i) = new File(s)
      Result.done

  given Value[UUID]:
    def typeName         = "uuid"
    def parse(s: String) =
      try Ok(UUID.fromString(s.trim))
      catch case _: IllegalArgumentException => Err(s"'$s' is not a valid UUID")

  // platform-dependent value instances (java.nio.file.Path is unavailable on Scala.js,
  // java.time outside the JVM); exported so they stay in this companion's implicit scope
  export flagged.internal.PlatformValues.given

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
