package flagged.internal

import compiletime.{summonFrom, erasedValue, constValue}
import compiletime.ops.int./
import flagged.meta.{Ann, AnnotMirror}

/** flagged's annotations on a type or an enum case, extracted at compile time from an
  * [[AnnotMirror]] — fully typed, no `Any` and no runtime type tests.
  */
final case class TargetAnnots(
    name: Option[String],
    help: Option[String],
    hidden: Boolean = false,
    version: Option[String] = None,
    aliases: List[String] = Nil,
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
    aliases: List[String] = Nil
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

  def makeProduct[A](onType: TargetAnnots, perField: Seq[FieldAnnots]): Annots.Product[A] =
    Annots.Product(onType, perField.toIndexedSeq)

  def makeSum[A](onType: TargetAnnots, perCase: Seq[TargetAnnots]): Annots.Sum[A] =
    Annots.Sum(onType, perCase.toIndexedSeq)

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

  private inline def const1[Args]: Any = constValue[Tuple.Head[Args & NonEmptyTuple]]

  inline def targetAnnotsOfSome[Anns]: TargetAnnots =
    collectTarget[Anns](TargetAnnots.empty, Nil)

  inline def collectTarget[Anns](acc: TargetAnnots, revNames: List[String]): TargetAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple =>
        val names = revNames.reverse
        acc.copy(name = names.headOption, aliases = names.drop(1))
      case _: (Ann[flagged.name, args, ?] *: t) =>
        collectTarget[t](acc, const1[args].asInstanceOf[String] :: revNames)
      case _: (Ann[flagged.help, args, ?] *: t) =>
        collectTarget[t](acc.copy(help = Some(const1[args].asInstanceOf[String])), revNames)
      case _: (Ann[flagged.version, args, ?] *: t) =>
        collectTarget[t](acc.copy(version = Some(const1[args].asInstanceOf[String])), revNames)
      case _: (Ann[flagged.hidden, ?, ?] *: t) =>
        collectTarget[t](acc.copy(hidden = true), revNames)
      case _: (Ann[flagged.default, ?, ?] *: t) =>
        collectTarget[t](acc.copy(default = true), revNames)
      case _: (_ *: t) =>
        collectTarget[t](acc, revNames)

  inline def fieldAnnotsOf[Anns]: FieldAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple => FieldAnnots.empty
      case _             => fieldAnnotsOfSome[Anns]

  inline def fieldAnnotsOfSome[Anns]: FieldAnnots =
    collectField[Anns](FieldAnnots.empty, Nil)

  inline def collectField[Anns](acc: FieldAnnots, revNames: List[String]): FieldAnnots =
    inline erasedValue[Anns] match
      case _: EmptyTuple =>
        val names = revNames.reverse
        acc.copy(name = names.headOption, aliases = names.drop(1))
      case _: (Ann[flagged.name, args, ?] *: t) =>
        collectField[t](acc, const1[args].asInstanceOf[String] :: revNames)
      case _: (Ann[flagged.short, args, ?] *: t) =>
        collectField[t](acc.copy(short = Some(const1[args].asInstanceOf[Char])), revNames)
      case _: (Ann[flagged.help, args, ?] *: t) =>
        collectField[t](acc.copy(help = Some(const1[args].asInstanceOf[String])), revNames)
      case _: (Ann[flagged.group, args, ?] *: t) =>
        collectField[t](acc.copy(group = Some(const1[args].asInstanceOf[String])), revNames)
      case _: (Ann[flagged.positional, ?, ?] *: t) =>
        collectField[t](acc.copy(positional = true), revNames)
      case _: (Ann[flagged.hidden, ?, ?] *: t) =>
        collectField[t](acc.copy(hidden = true), revNames)
      case _: (_ *: t) =>
        collectField[t](acc, revNames)

  // both walks halve the slot tuple (inline depth O(log n), matching Derive.walk) — annotation
  // slots hold only literal constant types, which survive the destructuring binders

  type Half[T <: Tuple] = Tuple.Size[T] / 2

  inline def fieldAnnotsEach[Slots]: List[FieldAnnots] =
    inline erasedValue[Slots] match
      case _: EmptyTuple        => Nil
      case _: (h *: EmptyTuple) => fieldAnnotsOf[h] :: Nil
      case _: (h *: t)          =>
        fieldAnnotsEach[Tuple.Take[h *: t, Half[h *: t]]] :::
          fieldAnnotsEach[Tuple.Drop[h *: t, Half[h *: t]]]

  inline def targetAnnotsEach[Slots]: List[TargetAnnots] =
    inline erasedValue[Slots] match
      case _: EmptyTuple        => Nil
      case _: (h *: EmptyTuple) => targetAnnotsOf[h] :: Nil
      case _: (h *: t)          =>
        targetAnnotsEach[Tuple.Take[h *: t, Half[h *: t]]] :::
          targetAnnotsEach[Tuple.Drop[h *: t, Half[h *: t]]]
