package flagged

import java.io.File
import java.util.UUID
import scala.collection.Factory
import scala.collection.immutable.ArraySeq
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
    * the hot path allocates nothing. Abstract: every shape implements its own reading, so coverage
    * is checked by the compiler rather than a cast.
    */
  private[flagged] def readInto(s: String, out: Array[Any], i: Int): Result[Unit, String]

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

  /** [[help]] including `@hidden` options and subcommands — what `--help-all` prints. */
  final def helpAll: String = helpAll(typeName)

  final def helpAll(prog: String): String = HelpFmt.render(command, prog, Nil, showHidden = true)

object Parser extends ParserLowPriority:
  def apply[A](using p: Parser[A]): Parser[A] = p

  /** Engine protocol: per-parse accumulator for one repeated slot. The engine [[offer]]s each raw
    * element token — parsed by [[read]] into the slot (scratch until finish) and accumulated on
    * success; [[finishInto]] writes the combined collection into the slot. [[size]] counts what was
    * accumulated, so the engine can skip the build when any element failed to parse. Owning the
    * element parse lets a collector reuse per-parse scratch across elements (the `Map` instance's
    * pair slots).
    */
  private[flagged] abstract class Collector:
    private var n = 0

    final def size: Int = n

    final def offer(s: String, out: Array[Any], i: Int): Result[Unit, String] =
      val r = read(s, out, i)
      r match
        case _: Err[?] => ()
        case _         =>
          append(out(i))
          n += 1
      r

    protected def read(s: String, out: Array[Any], i: Int): Result[Unit, String]

    /** Called with `size` still at the pre-insertion count. */
    protected def append(v: Any): Unit
    def finishInto(out: Array[Any], i: Int): Result[Unit, String]

  object Collector:
    class WrapperCollector[A, B](inner: Collector, f: A => Result[B, String]) extends Collector:
      // the inner offer both parses and accumulates, so this wrapper's append adds nothing
      protected def read(s: String, out: Array[Any], i: Int) = inner.read(s, out, i)
      protected def append(v: Any)                           = inner.append(v)
      def finishInto(out: Array[Any], i: Int)                =
        Result.task:
          inner.finishInto(out, i).check
          out(i) = f(out(i).asInstanceOf[A]).ok

  /** A [[Collector]] appending to a collection builder; the built collection is the slot value. */
  private[flagged] final class BuilderCollector[E](
      elem: Value[E],
      b: scala.collection.mutable.Builder[E, Any]
  ) extends Collector:
    protected def read(s: String, out: Array[Any], i: Int) = elem.readInto(s, out, i)
    protected def append(v: Any)                           = b += v.asInstanceOf[E]
    def finishInto(out: Array[Any], i: Int)                =
      Result.task:
        out(i) = b.result()

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
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int): Result[Unit, String]

    def emap[B](f: A => Result[B, String]): Value[B] = new Value[B]:
      def typeName                                                               = self.typeName
      override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
        Result.task:
          self.readInto(s, out, i).check
          out(i) = f(out(i).asInstanceOf[A]).ok

    /** Rename only: delegates parsing, keeping the original's allocation-free slot path. */
    def withTypeName(name: String): Value[A] = new Value[A]:
      def typeName                                                               = name
      override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
        self.readInto(s, out, i)

  /** A value parser for an enum with parameterless cases, matched by its kebab-cased case name:
    * `enum Color derives Parser.Enumerated`.
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

    /** A flag takes no token; reading one is an error ([[ValuedFlag]] overrides). */
    private[flagged] def readInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
      Result.task:
        eval.raise(s"'$s': this flag does not take a value")

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
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      intoSlot(fromValue(s), out, i)
    override def emap[B](f: A => Result[B, String]): ValuedFlag[B] =
      flag(n => fromCount(n).flatMap(f), s => fromValue(s).flatMap(f))

  /** Any number of occurrences, each parsed by a `Value` element (so repeats cannot nest, by
    * construction) and accumulated by a per-parse [[Collector]]. The collector is also finished
    * empty when the argument is absent, so a `Parser.repeated` combining function may reject zero
    * occurrences.
    */
  sealed trait Repeated[A] extends Parser[A]:
    self =>
    type Elem
    def element: Value[Elem]
    final def typeName: String = element.typeName

    /** Engine protocol: a fresh accumulator for one parse. Collection instances append straight to
      * the collection's builder — elements are materialised exactly once; `Parser.repeated`
      * combinators collect an `IndexedSeq` for their combining function.
      */
    private[flagged] def collector(): Collector

    /** One token reads as a single element, built immediately. */
    private[flagged] def readInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
      Result.task:
        val c = collector()
        c.offer(s, out, i).check
        c.finishInto(out, i).check

    def emap[B](f: A => Result[B, String]): Repeated[B] = new Repeated[B]:
      type Elem = self.Elem
      def element                      = self.element
      private[flagged] def collector() = new Collector.WrapperCollector(self.collector(), f)

  /** The raw arguments after `--`, taken verbatim; `build` combines them (also invoked with `Nil`
    * when no `--` is given — return `Err` to require one).
    */
  sealed trait Trailing[A] extends Parser[A]:
    def build(l: List[String]): Result[A, String]
    final def typeName: String                          = "args"
    def emap[B](f: A => Result[B, String]): Trailing[B] = trailing(l => build(l).flatMap(f))

    /** One token builds as a single trailing argument. */
    private[flagged] def readInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
      intoSlot(build(List(s)), out, i)

  /** A single command's grammar: named options, positionals, trailing, splices. As a field of
    * another command, its options are spliced in.
    */
  sealed trait Command[A] extends Parser[A]:
    private[flagged] def impl: flagged.internal.Command
    def prog: String
    final def typeName: String = prog

    /** A command has no single-token reading. */
    private[flagged] def readInto(s: String, out: Array[Any], i: Int): Result[Unit, String] =
      Result.task:
        eval.raise(s"'$s': '$prog' is a command parser, not a single value")

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
    def typeName                                                               = name
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      intoSlot(f(s), out, i)

  private[flagged] def enumeratedOf[A](name: String, pairs: Vector[(String, A)]): Enumerated[A] =
    new Enumerated[A]:
      private val names  = pairs.map(_(0)).toArray
      private val values = pairs.map(_(1)).toArray[Any]

      /** Index of the matching name, or -1. */
      private def indexOf(s: String): Int =
        val key = s.trim
        var i   = 0
        while i < names.length do
          if names(i) == key then return i
          i += 1
        -1
      def typeName                                                               = name
      override private[flagged] def readInto(s: String, out: Array[Any], k: Int) =
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
    * parser, and the collected elements are combined with `combine` (also invoked empty when the
    * argument is absent; return `Err` to require at least one occurrence).
    */
  def repeated[E, A](combine: IndexedSeq[E] => Result[A, String])(
      using elem: Value[E]
  ): Repeated[A] =
    new Repeated[A]:
      type Elem = E
      def element                      = elem
      private[flagged] def collector() = new Collector:
        private val b                                          = ArraySeq.untagged.newBuilder[Any]
        protected def read(s: String, out: Array[Any], i: Int) = elem.readInto(s, out, i)
        protected def append(v: Any)                           = b += v
        def finishInto(out: Array[Any], i: Int)                =
          intoSlot(combine(b.result().asInstanceOf[IndexedSeq[E]]), out, i)

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
    def element                      = elem
    private[flagged] def collector() = BuilderCollector(elem, List.newBuilder[A])

  given [A] => (elem: Value[A]) => Repeated[Vector[A]]:
    type Elem = A
    def element                      = elem
    private[flagged] def collector() = BuilderCollector(elem, Vector.newBuilder[A])

  given [A] => (elem: Value[A]) => Repeated[Seq[A]]:
    type Elem = A
    def element                      = elem
    private[flagged] def collector() = BuilderCollector(elem, ArraySeq.untagged.newBuilder[A])

  private final class PairValue[K, V](k: Value[K], v: Value[V]) extends Value[(K, V)]:
    def typeName = s"${k.typeName}=${v.typeName}"

    /** Parse into `out(i)` through caller-owned pair scratch (at least 2 slots). */
    def readInto(s: String, scratch: Array[Any], out: Array[Any], i: Int): Result[Unit, String] =
      Result.task:
        s.indexOf('=') match
          case -1 => eval.raise(s"'$s' is not in $typeName form")
          case eq =>
            k.readInto(s.take(eq), scratch, 0).check
            v.readInto(s.drop(eq + 1), scratch, 1).check
            out(i) = (scratch(0).asInstanceOf[K], scratch(1).asInstanceOf[V])

    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      readInto(s, new Array[Any](2), out, i)

  /** Each occurrence is one `key=value` entry, split at the first `=`; later entries win. */
  given [K, V] => (k: Value[K], v: Value[V]) => Repeated[Map[K, V]]:
    type Elem = (K, V)
    private val pair                 = PairValue(k, v)
    def element: Value[Elem]         = pair
    private[flagged] def collector() = new Collector:
      private val b     = Map.newBuilder[K, V]
      private val slots = new Array[Any](2) // pair scratch, reused across entries

      protected def read(s: String, out: Array[Any], i: Int) = pair.readInto(s, slots, out, i)
      protected def append(v: Any)                           = b += v.asInstanceOf[Elem]
      def finishInto(out: Array[Any], i: Int)                =
        Result.task:
          out(i) = b.result()

  // built-in instances implement `readInto` directly, so the default path is plain virtual
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
    def typeName                                                               = "string"
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      Result.task:
        out(i) = s

  /** Numeric instances share one shape; `f` is inlined, so each instance's parse body is direct
    * code — no function values, no `Ok` per token on the slot path.
    */
  @nowarn("msg=New anonymous class definition will be duplicated at each inline site")
  private inline def numValue[A](name: String)(inline f: String => A): Value[A] = new Value[A]:
    def typeName                                                               = name
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
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
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      internal.Runtime.parseBoolInto(s, out, i)

  given Value[Char]:
    def typeName                                                               = "char"
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      Result.task:
        if s.length != 1 then eval.raise(s"'$s' is not a single character")
        out(i) = s.charAt(0)

  given Value[File]:
    def typeName                                                               = "file"
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      Result.task:
        out(i) = new File(s)

  given Value[UUID]:
    def typeName                                                               = "uuid"
    override private[flagged] def readInto(s: String, out: Array[Any], i: Int) =
      Result.task:
        try out(i) = UUID.fromString(s.trim)
        catch case _: IllegalArgumentException => eval.raise(s"'$s' is not a valid UUID")

  // platform-dependent value instances (java.nio.file.Path is unavailable on Scala.js,
  // java.time outside the JVM); exported so they stay in this companion's implicit scope
  export flagged.internal.PlatformValues.given

/** Lower-priority instances, overridden by the dedicated ones in [[Parser]]. */
sealed trait ParserLowPriority:

  /** Repeated shape for any collection an implicit [[scala.collection.Factory]] can build (`Set`,
    * `ArraySeq`, sorted collections, `Array`, ...). The dedicated instances in [[Parser]] (`List`,
    * `Vector`, `Seq`, `Map`) take priority over this one.
    */
  // the Factory comes first: resolving it instantiates the element type A from C, which the
  // Value summon then uses
  given [A, C] => (factory: Factory[A, C], elem: Parser.Value[A]) => Parser.Repeated[C]:
    type Elem = A
    def element                      = elem
    private[flagged] def collector() = Parser.BuilderCollector(elem, factory.newBuilder)
