package flagged.internal

import compiletime.{summonFrom, summonInline, erasedValue, constValue}
import compiletime.ops.int./
import flagged.meta.{Ann, AnnotMirror, Defaults}

/** flagged's annotations on a type or an enum case, extracted at compile time from an
  * [[AnnotMirror]] — fully typed, no `Any` and no runtime type tests.
  */
final case class TargetAnnots(
    name: Option[String],
    help: Option[String],
    hidden: Boolean = false,
    aliases: IndexedSeq[String] = Vector.empty,
    default: Boolean = false
)

object TargetAnnots:
  val empty: TargetAnnots = TargetAnnots(None, None, false)

/** flagged's annotations on one constructor field, extracted at compile time. */
final case class FieldAnnots(
    name: Option[String],
    short: Option[Char],
    help: Option[String],
    positional: Boolean,
    hidden: Boolean = false,
    group: Option[String] = None,
    aliases: IndexedSeq[String] = Vector.empty,
    split: Option[Char] = None,
    greedy: Boolean = false
)

object FieldAnnots:
  val empty: FieldAnnots = FieldAnnots(None, None, None, false, false)

/** Runtime carrier for extracted annotations, built by `Derive.productAnnots` / `Derive.sumAnnots`.
  * Shaped like the type they describe: products carry per-field slots, sums per-case slots.
  */
enum Annots[A]:
  /** Annotations of a case class: on the type itself and per constructor field. */
  case Product(onType: TargetAnnots, perField: IndexedSeq[FieldAnnots])

  /** Annotations of an enum / sealed trait: on the type itself and per case. */
  case Sum(onType: TargetAnnots, perCase: IndexedSeq[TargetAnnots])

  def onType: TargetAnnots

object Annots:

  def makeProduct[A](onType: TargetAnnots, perField: IndexedSeq[FieldAnnots]): Annots.Product[A] =
    Annots.Product(onType, perField)

  def makeSum[A](onType: TargetAnnots, perCase: IndexedSeq[TargetAnnots]): Annots.Sum[A] =
    Annots.Sum(onType, perCase)

  /** Extract flagged's annotations for a product into typed records. */
  inline def productAnnots[A]: Annots.Product[A] =
    summonFrom:
      case am: AnnotMirror.Product[A] =>
        makeProduct[A](
          targetAnnotsOf[am.MirroredSelfAnnotations],
          fieldAnnotsEach[am.MirroredAnnotations]
        )

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
    collectField[Anns](Vector.empty, None, None, None, false, false, None, false)

  // inline parameters: arguments substitute as expressions, so pass-through values bind
  // nothing per step and a value replaced later in the walk is never constructed at all
  inline def collectField[Anns](
      inline names: Vector[String],
      inline short: Option[Char],
      inline help: Option[String],
      inline group: Option[String],
      inline positional: Boolean,
      inline hidden: Boolean,
      inline split: Option[Char],
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
          Some(const1[args, Char]),
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
              Some(const1[args, Char]),
              greedy
            )
          case _ =>
            val sep = summonInline[Defaults[flagged.split]].defaultArgument(0).asInstanceOf[Char]
            collectField[t](names, short, help, group, positional, hidden, Some(sep), greedy)
      case _: (Ann[flagged.greedy, ?, ?] *: t) =>
        collectField[t](names, short, help, group, positional, hidden, split, true)
      case _: (_ *: t) =>
        collectField[t](names, short, help, group, positional, hidden, split, greedy)

  // both walks halve the slot tuple (inline depth O(log n), matching Derive.walk) — annotation
  // slots hold only literal constant types, which survive the destructuring binders

  type Half[T <: Tuple] = Tuple.Size[T] / 2

  inline def fieldAnnotsEach[Slots]: IndexedSeq[FieldAnnots] =
    val b = Vector.newBuilder[FieldAnnots]
    fieldAnnotsInto[Slots](b)
    b.result()

  inline def fieldAnnotsInto[Slots](b: scala.collection.mutable.Growable[FieldAnnots]): Unit =
    inline erasedValue[Slots] match
      case _: EmptyTuple                   => ()
      case _: (a *: EmptyTuple)            => b += fieldAnnotsOf[a]
      case _: (a *: b0 *: EmptyTuple)      => b += fieldAnnotsOf[a] += fieldAnnotsOf[b0]
      case _: (a *: b0 *: c *: EmptyTuple) =>
        b += fieldAnnotsOf[a] += fieldAnnotsOf[b0] += fieldAnnotsOf[c]
      case _: (a *: b0 *: c *: d *: EmptyTuple) =>
        b += fieldAnnotsOf[a] += fieldAnnotsOf[b0] += fieldAnnotsOf[c] += fieldAnnotsOf[d]
      case _: (h *: t) =>
        fieldAnnotsInto[Tuple.Take[h *: t, Half[h *: t]]](b)
        fieldAnnotsInto[Tuple.Drop[h *: t, Half[h *: t]]](b)

  inline def targetAnnotsEach[Slots]: IndexedSeq[TargetAnnots] =
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
