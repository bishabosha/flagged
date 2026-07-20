package flagged

import java.io.File
import java.util.UUID
import scala.deriving.Mirror
import flagged.internal.{Assemble, Engine, HelpFmt}
import scala.annotation.nowarn
import Result.eval, eval.{check, ok}

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

  /** Engine protocol: parse one token into `out(i)` — one element for repeated parsers, the
    * explicit `--flag=value` form for valued flags. Returns the shared [[Result.done]] on success —
    * the hot path allocates nothing. `Value` and `ValuedFlag` override with their direct slot
    * writes.
    */
  private[flagged] def readInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
    Result.task:
      (this: @unchecked) match
        case _: Parser.Flag[A]     => eval.raise(s"'$s': this flag does not take a value")
        case r: Parser.Repeated[A] =>
          r.parseElemInto(s, out, i).check
          out(i) = r.buildErased(IndexedSeq(out(i))).ok
        case t: Parser.Trailing[A] => out(i) = t.build(List(s)).ok
        case c: Parser.Command[A]  =>
          eval.raise(s"'$s': '${c.prog}' is a command parser, not a single value")

  /** [[readInto]] boxed: parse a single token to a value (tests and diagnostics). */
  private[flagged] final def read(s: String): Result[A, String] =
    Result:
      val out = new Array[Any](1)
      readInto(s, out, 0).check
      out(0).asInstanceOf[A]

  /** The command grammar: command shapes directly; value shapes as a command line with one
    * positional argument.
    */
  private[flagged] final def command: internal.Command = this match
    case c: Parser.Command[?] => c.impl
    case _                    => Assemble.singleValueCommand(this)

  /** Parse `args`, reporting help/errors as values on the `Err` channel. */
  final def parse(args: Seq[String]): ParseResult[A] = parse(args, typeName)

  final def parse(args: Seq[String], prog: String): ParseResult[A] =
    Engine.run(command, prog, Nil, args.toIndexedSeq, 0).asInstanceOf[ParseResult[A]]

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
    * `Err` passes through unchanged (no re-wrapping).
    */
  private[flagged] def intoSlot[A](
      r: Result[A, String],
      out: Array[Any],
      i: Int
  ): Result[Unit, String] =
    Result.task:
      out(i) = r.ok

  /** One token per occurrence. */
  sealed trait Value[A] extends Parser[A]:
    self =>

    /** Engine protocol: parse `s` into `out(i)`; the shared [[Result.done]] on success (built-in
      * instances write the slot directly — no `Ok` per token).
      */
    private[flagged] def parseInto(s: String, out: Array[Any], i: Int): Result[Unit, String]
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      parseInto(s, out, i)

    def emap[B](f: A => Result[B, String]): Value[B] = new Value[B]:
      def typeName                                                       = self.typeName
      private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
        Result.task:
          self.parseInto(s, out, i).check
          out(i) = f(out(i).asInstanceOf[A]).ok

    /** Rename only: delegates parsing, keeping the original's allocation-free slot path. */
    def withTypeName(name: String): Value[A] = new Value[A]:
      def typeName                                                       = name
      private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
        self.parseInto(s, out, i)

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

    /** Whether this flag accepts the explicit `--flag=value` form ([[ValuedFlag]] does). */
    private[flagged] def takesValue: Boolean = false

    /** Engine protocol: build from the mention count into `out(i)`. */
    private[flagged] def countInto(n: Int, out: Array[Any], i: Int): Result[Unit, String] =
      intoSlot(fromCount(n), out, i)

  /** A flag that additionally accepts the explicit `--flag=value` form (and is therefore usable
    * positionally and inside `Option`).
    */
  sealed trait ValuedFlag[A] extends Flag[A]:
    def fromValue(s: String): Result[A, String]
    override private[flagged] def takesValue: Boolean = true

    /** Engine protocol: parse the explicit `--flag=value` form into `out(i)`. */
    private[flagged] def fromValueInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
      intoSlot(fromValue(s), out, i)
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      fromValueInto(s, out, i)
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
    def typeName                                                       = name
    private[flagged] def parseInto(s: String, out: Array[Any], i: Int) = intoSlot(f(s), out, i)

  private[flagged] def enumeratedOf[A](name: String, pairs: Vector[(String, A)]): Enumerated[A] =
    new Enumerated[A]:
      private val names  = pairs.map(_(0)).toArray
      private val values = pairs.map(_(1)).toArray[Any]

      /** Index of the case-insensitive match, or -1. */
      private def indexOf(s: String): Int =
        val key = s.trim
        var i   = 0
        while i < names.length do
          if names(i).equalsIgnoreCase(key) then return i
          i += 1
        -1
      def typeName                                                       = name
      private[flagged] def parseInto(s: String, out: Array[Any], k: Int) =
        Result.task:
          val i = indexOf(s)
          if i < 0 then eval.raise(s"'$s' is not one of: ${names.mkString(", ")}")
          out(k) = values(i)

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
    def typeName = s"${k.typeName}=${v.typeName}"
    private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      Result.task:
        s.indexOf('=') match
          case -1 => eval.raise(s"'$s' is not in $typeName form")
          case eq =>
            val slots = new Array[Any](2)
            k.parseInto(s.take(eq), slots, 0).check
            v.parseInto(s.drop(eq + 1), slots, 1).check
            out(i) = (slots(0).asInstanceOf[K], slots(1).asInstanceOf[V])

  /** Each occurrence is one `key=value` entry, split at the first `=`; later entries win. */
  given [K, V] => (k: Value[K], v: Value[V]) => Repeated[Map[K, V]]:
    type Elem = (K, V)
    val element                    = PairValue(k, v)
    def build(l: IndexedSeq[Elem]) = Ok(l.toMap)

  // built-in instances implement `parseInto` directly, so the default path is plain virtual
  // dispatch with no closures; only user-constructed parsers (`of`, `emap`, ...) go through a
  // function value. `numInto` takes its conversion inline, so each numeric parse body is direct
  // code.
  private inline def numInto[A](s: String, name: String, out: Array[Any], i: Int)(
      inline f: String => A
  ): Result[Unit, String] =
    Result.task:
      try out(i) = f(s.trim)
      catch case _: NumberFormatException => eval.raise(s"'$s' is not a valid $name")

  given Value[String]:
    def typeName                                                       = "string"
    private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      Result.task:
        out(i) = s

  /** Numeric instances share one shape; `f` is inlined, so each instance's parse body is direct
    * code — no function values, no `Ok` per token on the slot path.
    */
  @nowarn("msg=New anonymous class definition will be duplicated at each inline site")
  private inline def numValue[A](name: String)(inline f: String => A): Value[A] = new Value[A]:
    def typeName                                                       = name
    private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      numInto(s, name, out, i)(f)

  given Value[Int]        = numValue("int")(_.toInt)
  given Value[Long]       = numValue("long")(_.toLong)
  given Value[Short]      = numValue("short")(_.toShort)
  given Value[Byte]       = numValue("byte")(_.toByte)
  given Value[Float]      = numValue("float")(_.toFloat)
  given Value[Double]     = numValue("double")(_.toDouble)
  given Value[BigInt]     = numValue("integer")(BigInt(_))
  given Value[BigDecimal] = numValue("decimal")(BigDecimal(_))

  // repetition policy belongs to the flag's count parser: repeating a Boolean flag replaces the
  // previous value (the last mention wins, like value options); counting is opt-in via Count
  given ValuedFlag[Boolean]:
    def fromCount(n: Int)    = Ok(n > 0)
    def fromValue(s: String) = internal.Runtime.parseBool(s)
    override private[flagged] def countInto(n: Int, out: Array[Any], i: Int) =
      Result.task:
        out(i) = n > 0
    override private[flagged] def fromValueInto(s: String, out: Array[Any], i: Int) =
      internal.Runtime.parseBoolInto(s, out, i)

  given Value[Char]:
    def typeName                                                       = "char"
    private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      Result.task:
        if s.length != 1 then eval.raise(s"'$s' is not a single character")
        out(i) = s.charAt(0)

  given Value[File]:
    def typeName                                                       = "file"
    private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      Result.task:
        out(i) = new File(s)

  given Value[UUID]:
    def typeName                                                       = "uuid"
    private[flagged] def parseInto(s: String, out: Array[Any], i: Int) =
      Result.task:
        try out(i) = UUID.fromString(s.trim)
        catch case _: IllegalArgumentException => eval.raise(s"'$s' is not a valid UUID")

  // platform-dependent value instances (java.nio.file.Path is unavailable on Scala.js,
  // java.time outside the JVM); exported so they stay in this companion's implicit scope
  export flagged.internal.PlatformValues.given
