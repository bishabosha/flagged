package flagged.internal

import steps.result.Result

/** Internal runtime model of a derived command. Public because macro-generated code at user call
  * sites must reference these types; not intended for direct use.
  */

/** Exposes the engine's value array as the `Product` a `Mirror#fromProduct` consumes — the
  * generated constructor call reads `productElement(n)` only, so no tuple is built or copied.
  */
final class ArrayProduct(arr: Array[Any]) extends Product:
  def canEqual(that: Any): Boolean = false
  def productArity: Int            = arr.length
  def productElement(n: Int): Any  = arr(n)
enum Mode:
  /** Flag: takes no token; built from the occurrence count (a [[flagged.Parser.ValuedFlag]]
    * additionally handles the explicit `--flag=value` form). If `optional` the field is an
    * `Option[_]`: absent means `None`, any presence wraps the built value in `Some`.
    */
  case Flag(parser: flagged.Parser.Flag[?], optional: Boolean)

  /** Option taking one value. If `optional` the field is an `Option[_]` and the parsed value is
    * wrapped in `Some`.
    */
  case Single(parser: flagged.Parser[?], optional: Boolean)

  /** Option that may appear multiple times; elements are parsed with the parser's element and
    * combined with its build from an indexed view (also invoked empty when absent; may fail, e.g.
    * to require at least one occurrence).
    */
  case Repeated(parser: flagged.Parser.Repeated[?])

final case class OptSpec(
    long: String,
    short: Option[Char],
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() => Any],
    hidden: Boolean = false,
    group: Option[String] = None,
    aliases: List[String] = Nil
):
  lazy val longDisplay: String  = "--" + long
  lazy val shortDisplay: String = short.fold(longDisplay)("-" + _)

final case class PosSpec(
    name: String,
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() => Any]
):
  lazy val display: String = "<" + name + ">"

final case class SubCase(
    name: String,
    help: String,
    command: Command,
    hidden: Boolean = false,
    aliases: List[String] = Nil
)

final case class SubGroup(
    index: Int,
    optional: Boolean,
    default: Option[() => Any],
    cases: Vector[SubCase],
    defaultCase: Option[SubCase] = None // @default: run when no command token is given
)

/** An options group spliced into a parent command: the child command's option specs live re-indexed
  * in the parent (at `offset ..< offset + command.arity` of the parent's value storage), and the
  * built child value lands in parent slot `slot`. If `optional` the field is an `Option[_]`: the
  * group is `None` unless at least one of its options occurs on the command line. A field default
  * plays the same role for a non-`Option` group: it is used, and the group is not built, when none
  * of its options occur.
  */
final case class Splice(
    slot: Int,
    offset: Int,
    command: Command,
    optional: Boolean = false,
    default: Option[() => Any] = None
):
  /** Whether any of this splice's slots was mentioned on the command line. */
  def mentioned(counts: Array[Int], base: Int): Boolean =
    var i   = base + offset
    val end = base + offset + command.arity
    while i < end do
      if counts(i) > 0 then return true
      i += 1
    false

  /** Whether the group is left unbuilt, falling back to `None` or the field default (its required
    * options are then not enforced). Inline: as an outlined call it perturbs the JIT's inlining
    * plan for the parse path enough to cost ~50% on the group benchmark.
    */
  inline def skipped(counts: Array[Int], base: Int): Boolean =
    (optional || default.nonEmpty) && !mentioned(counts, base)

/** A field collecting the raw arguments after `--`, verbatim. */
final case class TrailingSpec(
    index: Int,
    help: String,
    parser: flagged.Parser.Trailing[?],
    optional: Boolean,
    default: Option[() => Any]
):
  def build(l: List[String]): Result[Any, String] = parser.build(l)

final case class Command(
    description: String,
    opts: Vector[OptSpec],
    positionals: Vector[PosSpec],
    sub: Option[SubGroup],
    trailing: Option[TrailingSpec],
    splices: List[Splice],
    build: Array[Any] => Result[Any, String], // fallible: `emap` validation composes here
    arity: Int, // value-storage size: own fields plus spliced children's storage
    version: Option[() => String] = None // from Versioned[A]; called by --version and help
):
  // per-token lookups: java.util maps return null instead of allocating an Option, and the
  // long keys carry their `--` prefix so a plain long token needs no substring at all (and the
  // key doubles as the option's display spelling)
  lazy val longLookup: java.util.HashMap[String, OptSpec] =
    val m = new java.util.HashMap[String, OptSpec]
    opts.foreach { o =>
      m.put(o.longDisplay, o)
      o.aliases.foreach(a => m.put("--" + a, o))
    }
    m
  private lazy val shorts: Vector[OptSpec] = opts.filter(_.short.isDefined)
  lazy val shortChars: Array[Char]         = shorts.map(_.short.get).toArray
  lazy val shortSpecs: Array[OptSpec]      = shorts.toArray

  // hot-loop views: Vector.apply walks a tree per element; the engine indexes these instead
  lazy val optSpecs: Array[OptSpec] = opts.toArray
  lazy val posSpecs: Array[PosSpec] = positionals.toArray

  /** Build spliced children from their storage slices, then build this command's value; the first
    * failing build (e.g. an `emap` validation) short-circuits. `counts` holds per-slot mention
    * counts, indexed from `base` for this command's storage: a skipped splice (see
    * [[Splice.skipped]]) becomes `None` or its field default without being built.
    */
  def finish(values: Array[Any], counts: Array[Int], base: Int): Result[Any, String] =
    // fast path: keeps the hot no-splice case free of the splice loop's bytecode, which the JIT
    // otherwise weighs against inlining `finish` into the parse path
    if splices.isEmpty then build(values) else finishSplices(values, counts, base)

  private def finishSplices(
      values: Array[Any],
      counts: Array[Int],
      base: Int
  ): Result[Any, String] =
    def loop(remaining: List[Splice]): Result[Any, String] = remaining match
      case Nil       => build(values)
      case s :: rest =>
        if s.skipped(counts, base) then
          values(s.slot) = s.default match
            case Some(d) => d()
            case None    => None
          loop(rest)
        else
          s.command.finish(
            values.slice(s.offset, s.offset + s.command.arity),
            counts,
            base + s.offset
          ) match
            case Result.Ok(v) =>
              values(s.slot) = if s.optional then Some(v) else v
              loop(rest)
            case err => err
    loop(splices)

object Command:
  /** A command with no parameters that always produces `value` (parameterless enum case / case
    * object).
    */
  def leaf(value: Any, description: String): Command =
    Command(description, Vector.empty, Vector.empty, None, None, Nil, _ => Result.Ok(value), 0)
