package flagged.internal

import steps.result.Result
import scala.annotation.publicInBinary

/** Internal runtime model of a derived command — `private[flagged]` like the rest of `internal`;
  * inline expansions reach the pieces they need through `@publicInBinary` terms.
  */

/** Exposes the engine's value array as the `Product` a `Mirror#fromProduct` consumes — the
  * generated constructor call reads `productElement(n)` only, so no tuple is built or copied. The
  * arity is explicit: under splices the array is the whole storage (the spliced children's slots
  * sit past the parent's own fields) and is passed without trimming.
  */
private[flagged] final class ArrayProduct @publicInBinary() (arr: Array[Any], n: Int)
    extends Product:
  def canEqual(that: Any): Boolean = false
  def productArity: Int            = n
  def productElement(i: Int): Any  = arr(i)
private[flagged] enum Mode:
  /** Flag: takes no token; built from the occurrence count (a [[flagged.Parser.ValuedFlag]]
    * additionally handles the explicit `--flag=value` form). If `optional` the field is an
    * `Option[_]`: absent means `None`, any presence wraps the built value in `Some`.
    */
  case Flag(parser: flagged.Parser.Flag[?], optional: Boolean)

  /** Option taking one value. If `optional` the field is an `Option[_]` and the parsed value is
    * wrapped in `Some`.
    */
  case Single(parser: flagged.Parser[?], optional: Boolean)

  /** Option spanning a fixed number of consecutive tokens, one per product element; parsed eagerly
    * at the occurrence, so repetition is last-wins by overwrite. If `optional` the field is an
    * `Option[_]` and the built value is wrapped in `Some`.
    */
  case Product(parser: flagged.Parser.Product[?], optional: Boolean)

  /** Option that may appear multiple times; elements are parsed with the parser's element and
    * combined with its build from an indexed view (also invoked empty when absent; may fail, e.g.
    * to require at least one occurrence). `split` (0 = none) divides each occurrence's value into
    * segments, each parsed as an element; `greedy` lets an occurrence consume the following free
    * tokens as further elements.
    */
  case Repeated(parser: flagged.Parser.Repeated[?], split: Char = 0, greedy: Boolean = false)

private[flagged] final case class OptSpec(
    long: String,
    short: Option[Char],
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() => Any],
    hidden: Boolean = false,
    group: Option[String] = None,
    aliases: IndexedSeq[String] = Vector.empty
):
  lazy val longDisplay: String  = "--" + long
  lazy val shortDisplay: String = short.fold(longDisplay)("-" + _)

private[flagged] final case class PosSpec(
    name: String,
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() => Any]
):
  lazy val display: String = "<" + name + ">"

private[flagged] final case class SubCase(
    name: String,
    help: String,
    command: Command,
    hidden: Boolean = false,
    aliases: IndexedSeq[String] = Vector.empty
)

private[flagged] final case class SubGroup(
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
private[flagged] final case class Splice(
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
private[flagged] final case class TrailingSpec(
    index: Int,
    help: String,
    parser: flagged.Parser.Trailing[?],
    optional: Boolean,
    default: Option[() => Any]
):
  def buildInto(l: IndexedSeq[String], out: Array[Any], i: Int): Result[Unit, String] =
    parser.buildInto(l, out, i)

private[flagged] final case class Command(
    description: String,
    opts: IArray[OptSpec],
    positionals: IArray[PosSpec],
    sub: Option[SubGroup],
    trailing: Option[TrailingSpec],
    splices: IndexedSeq[Splice],
    build: Array[Any] => Result[Any, String], // fallible: `emap` validation composes here
    arity: Int, // value-storage size: own fields plus spliced children's storage
    version: Option[() => String] = None, // from Versioned[A]; called by --version and help
    // per-token lookups, built during assembly (duplicate-name detection rides on the map
    // inserts): java.util maps return null instead of allocating an Option, and the long keys
    // carry their `--` prefix so a plain long token needs no substring at all (the key doubles
    // as the option's display spelling)
    longLookup: java.util.HashMap[String, OptSpec] = Command.noLookup,
    shortChars: Array[Char] = Command.noShortChars,
    shortSpecs: Array[OptSpec] = Command.noShortSpecs
):

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
    def loop(i: Int): Result[Any, String] =
      if i == splices.length then build(values)
      else
        val s = splices(i)
        if s.skipped(counts, base) then
          values(s.slot) = s.default match
            case Some(d) => d()
            case None    => None
          loop(i + 1)
        else
          s.command.finish(
            values.slice(s.offset, s.offset + s.command.arity),
            counts,
            base + s.offset
          ) match
            case Result.Ok(v) =>
              values(s.slot) = if s.optional then Some(v) else v
              loop(i + 1)
            case err => err
    loop(0)

private[flagged] object Command:
  // shared empties for option-less commands; the map is never mutated after construction
  private[internal] val noLookup     = new java.util.HashMap[String, OptSpec]
  private[internal] val noShortChars = Array.empty[Char]
  private[internal] val noShortSpecs = Array.empty[OptSpec]

  /** A command with no parameters that always produces `value` (parameterless enum case / case
    * object).
    */
  def leaf(value: Any, description: String): Command =
    Command(
      description,
      IArray.empty,
      IArray.empty,
      None,
      None,
      Vector.empty,
      _ => Result.Ok(value),
      0
    )
