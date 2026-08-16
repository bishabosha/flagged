package flagged.internal

import language.experimental.captureChecking
import language.experimental.separationChecking

import compiletime.{summonFrom, erasedValue, constValue}
import compiletime.ops.int./
import flagged.meta.{ArgumentList, AnnotMirror}
import scala.annotation.publicInBinary

/** Allocation-free optional character for derivation metadata. */
@publicInBinary private[flagged] object MaybeChar:
  final val empty: Int               = -1
  inline def apply(value: Char): Int = value.toInt

  /** The runtime encoding of an annotation's `Char` argument, whose "none" sentinel is
    * `'\u0000'`.
    */
  def encode(value: Char): Int = if value == '\u0000' then empty else value.toInt

/** Runtime carrier for annotations extracted at compile time. Only sums need one: a product's
  * fields are walked in [[Derive]], which extracts each slot as it reaches the field
  * ([[Annots.fieldAnnotsOf]]) and hands it straight to the builder, whereas a sum's cases are
  * assembled together.
  */
@publicInBinary private[flagged] object Annots:

  /** The record of a bare `@cmd` (and of an unannotated type or case): every argument at its
    * default — one shared instance, no constructor call per occurrence.
    */
  val bareCmd: flagged.cmd = flagged.cmd()

  /** The record of a bare `@opt` — the common named-option marker. */
  val bareOpt: flagged.opt = flagged.opt()

  /** The record of a field with no `@opt` in a positional-by-default derivation: positional, like a
    * `@scala.main` parameter.
    */
  val positionalOpt: flagged.opt = flagged.opt(positional = true)

  /** Materialise a mirrored `aliases` tuple as the runtime strings the specs store. */
  def aliasStrings(t: Tuple): IndexedSeq[String] =
    if t.productArity == 0 then Vector.empty
    else t.productIterator.map(_.asInstanceOf[String]).toVector

  /** Annotations of an enum / sealed trait: on the type itself and per case; `perCase` empty on an
    * inhabited sum means no case carries one — index through [[caseAnnots]].
    */
  final case class Sum[A](onType: flagged.cmd, perCase: IndexedSeq[flagged.cmd])

  extension (s: Annots.Sum[?])
    def caseAnnots(i: Int): flagged.cmd =
      if s.perCase.isEmpty then bareCmd else s.perCase(i)

  def makeSum[A](onType: flagged.cmd, perCase: IndexedSeq[flagged.cmd]): Annots.Sum[A] =
    Annots.Sum(onType, perCase)

  /** Extract flagged's annotations for a sum into materialised annotation instances. */
  inline def sumAnnots[A]: Annots.Sum[A] =
    summonFrom:
      case am: AnnotMirror.Sum[A] =>
        makeSum[A](
          targetAnnotsOf[am.MirroredSelfAnnotations],
          targetAnnotsEach[am.MirroredAnnotations]
        )

  inline def targetAnnotsOf[Anns]: flagged.cmd =
    inline erasedValue[Anns] match
      case _: EmptyTuple => bareCmd
      case _             => targetAnnotsOfSome[Anns]

  // Extraction materialises the slot's `@cmd` / `@opt` occurrence itself
  // (AnnotMirror.materialize): the explicit constants land in their parameter positions and the
  // Defaults mirror fills every omitted one; a bare occurrence shares its constant record
  // instead of calling the constructor.

  inline def targetAnnotsOfSome[Anns]: flagged.cmd =
    inline erasedValue[Anns] match
      case _: EmptyTuple                                   => bareCmd
      case _: (ArgumentList[flagged.cmd, ns, vs, is] *: ?) =>
        inline erasedValue[ns] match
          // bare @cmd: nothing to materialise — share the constant record
          case _: EmptyTuple => bareCmd
          case _             => AnnotMirror.materialize[flagged.cmd, vs, is]
      case _: (_ *: t) => targetAnnotsOfSome[t]

  /** The field record: from the slot's `@opt` when there is one — its `positional` starts `false`,
    * so an annotated field is a named option unless it says `positional = true` — otherwise the
    * no-`@opt` record selected by `positionalDefault` (positional in a command, named in a
    * `Parser.Shared` group).
    */
  inline def fieldAnnotsOf[Anns](inline positionalDefault: Boolean): flagged.opt =
    inline erasedValue[Anns] match
      case _: EmptyTuple => noOptAnnots(positionalDefault)
      case _             => fieldAnnotsOfSome[Anns](positionalDefault)

  inline def noOptAnnots(inline positionalDefault: Boolean): flagged.opt =
    inline if positionalDefault then positionalOpt else bareOpt

  inline def fieldAnnotsOfSome[Anns](inline positionalDefault: Boolean): flagged.opt =
    inline erasedValue[Anns] match
      case _: EmptyTuple                                   => noOptAnnots(positionalDefault)
      case _: (ArgumentList[flagged.opt, ns, vs, is] *: ?) =>
        inline erasedValue[ns] match
          // bare @opt — the common named-option marker: share the constant record instead of
          // calling the constructor
          case _: EmptyTuple => bareOpt
          case _             => AnnotMirror.materialize[flagged.opt, vs, is]
      case _: (_ *: t) => fieldAnnotsOfSome[t](positionalDefault)

  // the walk halves the slot tuple (inline depth O(log n), matching Derive.walk) — annotation
  // slots hold only literal constant types, which survive the destructuring binders

  type Half[T <: Tuple] = Tuple.Size[T] / 2

  /** Do none of the annotation slots hold anything? Then the walks share `Vector.empty` instead of
    * building an all-default vector. An inline match, not a match type: the slots arrive as an
    * abstract mirror path that only inline-match reduction resolves.
    */
  private transparent inline def allEmpty[Slots]: Boolean =
    inline erasedValue[Slots] match
      case _: EmptyTuple        => true
      case _: (EmptyTuple *: t) => allEmpty[t]
      case _: (_ *: _)          => false

  inline def targetAnnotsEach[Slots]: IndexedSeq[flagged.cmd] =
    inline if allEmpty[Slots] then Vector.empty
    else
      val b = Vector.newBuilder[flagged.cmd]
      targetAnnotsInto[Slots](b)
      b.result()

  inline def targetAnnotsInto[Slots](b: scala.collection.mutable.Growable[flagged.cmd]): Unit =
    inline erasedValue[Slots] match
      case _: EmptyTuple                   => ()
      case _: (a *: EmptyTuple)            => b += targetAnnotsOf[a]
      case _: (a *: b0 *: EmptyTuple)      => b += targetAnnotsOf[a] += targetAnnotsOf[b0]
      case _: (a *: b0 *: c *: EmptyTuple) =>
        b += targetAnnotsOf[a] += targetAnnotsOf[b0] += targetAnnotsOf[c]
      case _: (a *: b0 *: c *: d *: EmptyTuple) =>
        b += targetAnnotsOf[a] += targetAnnotsOf[b0] += targetAnnotsOf[c] += targetAnnotsOf[d]
      case _: (h *: t) =>
        targetAnnotsInto[Tuple.Take[h *: t, Half[h *: t]]](b)
        targetAnnotsInto[Tuple.Drop[h *: t, Half[h *: t]]](b)
