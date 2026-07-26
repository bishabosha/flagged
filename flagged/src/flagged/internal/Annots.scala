package flagged.internal

import compiletime.{summonFrom, summonInline, erasedValue, constValue}
import compiletime.ops.int./
import flagged.meta.{Ann, AnnotMirror, Defaults}
import scala.annotation.publicInBinary

/** Allocation-free optional character for derivation metadata. */
@publicInBinary private[flagged] object MaybeChar:
  final val empty: Int               = -1
  inline def apply(value: Char): Int = value.toInt

/** flagged's annotations on a type or an enum case, extracted at compile time from an
  * [[AnnotMirror]] — fully typed, no `Any` and no runtime type tests.
  */
private[flagged] final case class TargetAnnots @publicInBinary() (
    name: Option[String],
    help: Option[String],
    hidden: Boolean = false,
    aliases: IndexedSeq[String] = Vector.empty,
    default: Boolean = false
)

@publicInBinary private[flagged] object TargetAnnots:
  val empty: TargetAnnots = TargetAnnots(None, None, false)

/** flagged's annotations on one constructor field, extracted at compile time. */
private[flagged] final case class FieldAnnots @publicInBinary() (
    name: Option[String],
    short: Int,
    help: Option[String],
    positional: Boolean,
    hidden: Boolean = false,
    group: Option[String] = None,
    aliases: IndexedSeq[String] = Vector.empty,
    split: Int = MaybeChar.empty,
    greedy: Boolean = false
)

@publicInBinary private[flagged] object FieldAnnots:
  val empty: FieldAnnots = FieldAnnots(None, MaybeChar.empty, None, false, false)

/** Runtime carrier for annotations extracted at compile time. Only sums need one: a product's
  * fields are walked in [[Derive]], which extracts each slot as it reaches the field
  * ([[Annots.fieldAnnotsOf]]) and hands it straight to the builder, whereas a sum's cases are
  * assembled together.
  */
@publicInBinary private[flagged] object Annots:

  /** Annotations of an enum / sealed trait: on the type itself and per case; `perCase` empty on an
    * inhabited sum means no case carries one — index through [[caseAnnots]].
    */
  final case class Sum[A](onType: TargetAnnots, perCase: IndexedSeq[TargetAnnots])

  extension (s: Annots.Sum[?])
    def caseAnnots(i: Int): TargetAnnots =
      if s.perCase.isEmpty then TargetAnnots.empty else s.perCase(i)

  def makeSum[A](onType: TargetAnnots, perCase: IndexedSeq[TargetAnnots]): Annots.Sum[A] =
    Annots.Sum(onType, perCase)

  /** Extract flagged's annotations for a sum into typed records. */
  inline def sumAnnots[A]: Annots.Sum[A] =
    summonFrom:
      case am: AnnotMirror.Sum[A] =>
        makeSum[A](
          targetAnnotsOf[am.MirroredSelfAnnotations],
          targetAnnotsEach[am.MirroredAnnotations]
        )

  inline def targetAnnotsOf[Anns]: TargetAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple => TargetAnnots.empty
      case _             => targetAnnotsOfSome[Anns]

  // Extraction folds the annotation slot once, matching each mirrored occurrence against
  // flagged's annotations directly, instead of one AnnotMirror.find walk per annotation type.
  // Every flagged annotation has zero or one parameter and no defaults, so a matched occurrence
  // materialises as a single constValue — no Mirror/Defaults machinery per query.

  private inline def const1[Args, T]: T = constValue[Tuple.Head[Args & NonEmptyTuple] & T]

  inline def targetAnnotsOfSome[Anns]: TargetAnnots =
    collectTarget[Anns](Vector.empty, None, false, false)

  // the folds thread each extracted value as an inline parameter and call the constructor once
  // at the end — no intermediate copies, and superseded values are dropped unmaterialised
  inline def collectTarget[Anns](
      inline names: Vector[String],
      inline help: Option[String],
      inline hidden: Boolean,
      inline default: Boolean
  ): TargetAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple =>
        val ns = names
        TargetAnnots(ns.headOption, help, hidden, ns.drop(1), default)
      case _: (Ann[flagged.name, args, ?] *: t) =>
        collectTarget[t](names :+ const1[args, String], help, hidden, default)
      case _: (Ann[flagged.help, args, ?] *: t) =>
        collectTarget[t](names, Some(const1[args, String]), hidden, default)
      case _: (Ann[flagged.hidden, ?, ?] *: t) =>
        collectTarget[t](names, help, true, default)
      case _: (Ann[flagged.default, ?, ?] *: t) =>
        collectTarget[t](names, help, hidden, true)
      case _: (_ *: t) =>
        collectTarget[t](names, help, hidden, default)

  inline def fieldAnnotsOf[Anns]: FieldAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple => FieldAnnots.empty
      case _             => fieldAnnotsOfSome[Anns]

  inline def fieldAnnotsOfSome[Anns]: FieldAnnots =
    collectField[Anns](
      Vector.empty,
      MaybeChar.empty,
      None,
      None,
      false,
      false,
      MaybeChar.empty,
      false
    )

  // inline parameters: arguments substitute as expressions, so pass-through values bind
  // nothing per step and a value replaced later in the walk is never constructed at all
  inline def collectField[Anns](
      inline names: Vector[String],
      inline short: Int,
      inline help: Option[String],
      inline group: Option[String],
      inline positional: Boolean,
      inline hidden: Boolean,
      inline split: Int,
      inline greedy: Boolean
  ): FieldAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple =>
        val ns = names
        FieldAnnots(
          ns.headOption,
          short,
          help,
          positional,
          hidden,
          group,
          ns.drop(1),
          split,
          greedy
        )
      case _: (Ann[flagged.name, args, ?] *: t) =>
        collectField[t](
          names :+ const1[args, String],
          short,
          help,
          group,
          positional,
          hidden,
          split,
          greedy
        )
      case _: (Ann[flagged.short, args, ?] *: t) =>
        collectField[t](
          names,
          MaybeChar(const1[args, Char]),
          help,
          group,
          positional,
          hidden,
          split,
          greedy
        )
      case _: (Ann[flagged.help, args, ?] *: t) =>
        collectField[t](
          names,
          short,
          Some(const1[args, String]),
          group,
          positional,
          hidden,
          split,
          greedy
        )
      case _: (Ann[flagged.group, args, ?] *: t) =>
        collectField[t](
          names,
          short,
          help,
          Some(const1[args, String]),
          positional,
          hidden,
          split,
          greedy
        )
      case _: (Ann[flagged.positional, ?, ?] *: t) =>
        collectField[t](names, short, help, group, true, hidden, split, greedy)
      case _: (Ann[flagged.hidden, ?, ?] *: t) =>
        collectField[t](names, short, help, group, positional, true, split, greedy)
      case _: (Ann[flagged.split, args, dflt] *: t) =>
        // the separator may be defaulted (`@split`): resolve it through the Defaults mirror
        inline erasedValue[dflt] match
          case _: (false *: EmptyTuple) =>
            collectField[t](
              names,
              short,
              help,
              group,
              positional,
              hidden,
              MaybeChar(const1[args, Char]),
              greedy
            )
          case _ =>
            val sep = summonInline[Defaults[flagged.split]].defaultArgument(0).asInstanceOf[Char]
            collectField[t](
              names,
              short,
              help,
              group,
              positional,
              hidden,
              MaybeChar(sep),
              greedy
            )
      case _: (Ann[flagged.greedy, ?, ?] *: t) =>
        collectField[t](names, short, help, group, positional, hidden, split, true)
      case _: (_ *: t) =>
        collectField[t](names, short, help, group, positional, hidden, split, greedy)

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

  inline def targetAnnotsEach[Slots]: IndexedSeq[TargetAnnots] =
    inline if allEmpty[Slots] then Vector.empty
    else
      val b = Vector.newBuilder[TargetAnnots]
      targetAnnotsInto[Slots](b)
      b.result()

  inline def targetAnnotsInto[Slots](b: scala.collection.mutable.Growable[TargetAnnots]): Unit =
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
