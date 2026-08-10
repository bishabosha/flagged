package flagged.internal

import language.experimental.captureChecking
import language.experimental.separationChecking

import steps.result.Result
import steps.result.Result.eval.check
import scala.annotation.publicInBinary
import scala.collection.immutable.IntMap

/** Internal runtime model of a derived command — `private[flagged]` like the rest of `internal`;
  * inline expansions reach the pieces they need through `@publicInBinary` terms.
  */

/** [[scala.caps.freeze]] behind an inlined indirection: the capture-checked branch delegates to
  * `caps.freeze`, and the Uncheck rewrite reduces the body to its argument, so derived branches
  * keep identical call sites with no reference to the experimental API. Pass a freshly built value:
  * `caps.freeze`'s capture-stripping reaches through the transparent inline only for unbound
  * temporaries, not already-bound locals.
  */
private[flagged] transparent inline def frozen(
    inline x: caps.Mutable | Array[?]
): caps.Mutable | Array[?] =
  caps.freeze(x)

/** A pure [[IArray]] from a pure array. `IArray.unsafeFromArray`'s result is always fresh under
  * separation checking and cannot flow into pure positions such as `Tuple.fromIArray`'s parameter;
  * this cast is capture-free — argument and result are both pure and the two types are
  * erased-identical.
  */
private[flagged] inline def frozenIArray[T](x: Array[T]^{}): IArray[T] =
  x.asInstanceOf[IArray[T]]

/** Exposes the engine's value array as the `Product` a `Mirror#fromProduct` consumes — the
  * generated constructor call reads `productElement(n)` only, so no tuple is built or copied. The
  * arity is explicit: under splices the array is the whole storage (the spliced children's slots
  * sit past the parent's own fields) and is passed without trimming.
  */
private[flagged] final class ArrayProduct @publicInBinary() (
    // untracked: the one borrow capture checking cannot express — this wrapper aliases the
    // engine's live storage but only reads, and `Mirror#fromProduct` consumes it before the
    // engine writes again (see Derive.arrayProduct). A read-only view (`Array[Any]^{caps.any.rd}`)
    // does not help: `{any.rd}` is still a capture, and `Mirror#fromProduct(p: Product)` requires
    // capture set `{}` — only a capture-polymorphic `fromProduct` upstream could lift this.
    @scala.caps.unsafe.untrackedCaptures arr: Array[Any],
    offset: Int,
    n: Int
) extends Product:
  @publicInBinary def this(arr: Array[Any], n: Int) = this(arr, 0, n)
  def canEqual(that: Any): Boolean = false
  def productArity: Int            = n
  def productElement(i: Int): Any  = arr(offset + i)

private[flagged] enum Mode:
  /** Flag: takes no token; built from the occurrence count (a [[flagged.Parser.ValuedFlag]]
    * additionally handles the explicit `--flag=value` form). If `optional` the field is an
    * `Option[_]`: absent means `None`, any presence wraps the built value in `Some`.
    */
  case Flag(parser: flagged.Parser.Flag[?], optional: Boolean)

  /** Option taking one value. If `optional` the field is an `Option[_]` and the parsed value is
    * wrapped in `Some`.
    */
  case Single(parser: flagged.Parser.SingleToken[?], optional: Boolean)

  /** Option spanning a fixed number of consecutive tokens, one per product element; parsed eagerly
    * at the occurrence, so repetition is last-wins by overwrite. If `optional` the field is an
    * `Option[_]` and the built value is wrapped in `Some`.
    */
  case Product(parser: flagged.Parser.Product[?], optional: Boolean)

  /** Option that may appear multiple times; elements are parsed with the parser's element and
    * combined with its build from an indexed view (also invoked empty when absent; may fail, e.g.
    * to require at least one occurrence). A non-negative `split` is the unsigned `Char` value that
    * divides each occurrence into segments; `greedy` lets an occurrence consume the following free
    * tokens as further elements. `-1` means no split.
    */
  case Repeated(
      parser: flagged.Parser.Repeated[?],
      split: Int = MaybeChar.empty,
      greedy: Boolean = false
  )

/** The validation/materialisation view shared by named and positional fields. */
private[flagged] sealed trait SlotSpec:
  def index: Int
  def mode: Mode
  def default: Option[() -> Any]
  def display: String

private[flagged] final case class OptSpec(
    long: String,
    short: Int,
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() -> Any],
    hidden: Boolean = false,
    group: Option[String] = None,
    aliases: IndexedSeq[String] = Vector.empty
) extends SlotSpec:
  lazy val longDisplay: String  = "--" + long
  lazy val shortDisplay: String = if short < 0 then longDisplay else "-" + short.toChar
  def display: String           = longDisplay

private[flagged] final case class PosSpec(
    name: String,
    help: String,
    metavar: String,
    index: Int,
    mode: Mode,
    default: Option[() -> Any]
) extends SlotSpec:
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
    default: Option[() -> Any],
    cases: Vector[SubCase],
    defaultCase: Option[SubCase] = None // @cmd(default = true): run when no command token is given
)

/** An options group spliced into a parent command: the child command's option specs live re-indexed
  * in the parent (at `offset ..< offset + command.arity` of the parent's value storage), and the
  * built child value lands in parent slot `slot`. If `optional` the field is an `Option[_]`: the
  * group is `None` unless at least one of its options occurs on the command line. A field default
  * plays the same role for a non-`Option` group: it is used, and the group is not built, when none
  * of its options occur.
  */
private[flagged] trait Mentions:
  def isSeen(index: Int): Boolean

private[flagged] final case class Splice(
    slot: Int,
    offset: Int,
    command: Command,
    optional: Boolean = false,
    default: Option[() -> Any] = None
):
  // Storage layout invariant: the parent's own field slots (including this splice's destination
  // `slot`) all sit below the spliced children's storage, so a child build never reads the slot
  // it writes.
  assert(slot < offset)

  /** Whether any of this splice's slots was mentioned on the command line. */
  def mentioned(mentions: Mentions^{caps.any.rd}, base: Int): Boolean =
    var i   = base + offset
    val end = base + offset + command.arity
    while i < end do
      if mentions.isSeen(i) then return true
      i += 1
    false

  /** Whether the group is left unbuilt, falling back to `None` or the field default (its required
    * options are then not enforced). Inline: as an outlined call it perturbs the JIT's inlining
    * plan for the parse path enough to cost ~50% on the group benchmark.
    */
  inline def skipped(mentions: Mentions^{caps.any.rd}, base: Int): Boolean =
    (optional || default.nonEmpty) && !mentioned(mentions, base)

/** A field collecting the raw arguments after `--`, verbatim. */
private[flagged] final case class TrailingSpec(
    index: Int,
    help: String,
    parser: flagged.Parser.Trailing[?],
    optional: Boolean,
    default: Option[() -> Any]
):
  def buildInto(l: IndexedSeq[String], out: Array[Any]^, i: Int): Result[Unit, String] =
    parser.buildInto(l, out, i)

private[flagged] final case class Command(
    description: String,
    opts: Array[OptSpec]^{},
    positionals: Array[PosSpec]^{},
    sub: Option[SubGroup],
    trailing: Option[TrailingSpec],
    splices: Array[Splice]^{},
    // Destination-oriented so builds write straight into a slot of the one storage array they
    // read from — no value-bearing Results, no input slicing, and a single exclusive array
    // parameter, so separation checking accepts the finish path without escape hatches.
    build: (Array[Any]^, Int, Int) -> Result[Unit, String],
    arity: Int, // value-storage size: own fields plus spliced children's storage
    version: Option[() -> String] = None, // from Versioned[A]; called by --version and help
    // Per-token lookups, built during assembly. Long keys carry their `--` prefix so a plain long
    // token needs no substring (the key doubles as the option's display spelling). IntMap keeps
    // short-option characters unboxed without a linear scan.
    longLookup: java.util.HashMap[String, OptSpec] = Command.noLookup,
    shortLookup: IntMap[OptSpec] = Command.noShortLookup
):

  /** Build spliced children from their storage ranges, then build this command's value into
    * `values(outIndex)`; the first failing build (e.g. an `emap` validation) short-circuits.
    * `skips` holds `base`-relative destination slots of skipped splices, precomputed by the engine
    * (see [[Splice.skipped]]): such a splice becomes `None` or its field default without being
    * built.
    */
  def finishInto(
      values: Array[Any]^,
      skips: collection.Set[Int],
      base: Int,
      outIndex: Int
  ): Result[Unit, String] =
    // fast path: keeps the hot no-splice case free of the splice loop's bytecode, which the JIT
    // otherwise weighs against inlining `finish` into the parse path
    if splices.isEmpty then build(values, base, outIndex)
    else finishSplicesInto(values, skips, base, outIndex)

  private def finishSplicesInto(
      values: Array[Any]^,
      skips: collection.Set[Int],
      base: Int,
      outIndex: Int
  ): Result[Unit, String] =
    Result.task:
      var i = 0
      while i < splices.length do
        val s = splices(i)
        if skips(base + s.slot) then
          values(base + s.slot) = s.default match
            case Some(d) => d()
            case None    => None
        else
          s.command.finishInto(values, skips, base + s.offset, base + s.slot).check
          if s.optional then values(base + s.slot) = Some(values(base + s.slot))
        i += 1
      build(values, base, outIndex).check

private[flagged] object Command:
  // shared empties for option-less commands; the map is never mutated after construction
  private[internal] val noLookup      = new java.util.HashMap[String, OptSpec]
  private[internal] val noShortLookup = IntMap.empty[OptSpec]

  // frozen shared empties: `caps.freeze` consumes the fresh array and strips its capture set,
  // so the spec storage is pure (and therefore read-only) from assembly onward
  private[internal] val noOpts: Array[OptSpec]^{}   = frozen(Array.empty[OptSpec])
  private[internal] val noPos: Array[PosSpec]^{}    = frozen(Array.empty[PosSpec])
  private[internal] val noSplices: Array[Splice]^{} = frozen(Array.empty[Splice])

  /** A command with no parameters that always produces `value` (parameterless enum case / case
    * object).
    */
  def leaf(value: Any, description: String): Command =
    Command(
      description,
      noOpts,
      noPos,
      None,
      None,
      noSplices,
      (arr, _, i) =>
        Result.task:
          arr(i) = value
      ,
      0
    )
