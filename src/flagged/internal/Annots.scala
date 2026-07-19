package flagged.internal

import compiletime.{summonFrom, erasedValue}
import compiletime.ops.int./
import flagged.meta.AnnotMirror

/** flagged's annotations on a type or an enum case, extracted at compile time from an
  * [[AnnotMirror]] — fully typed, no `Any` and no runtime type tests.
  */
final case class TargetAnnots(
    name: Option[String],
    help: Option[String],
    hidden: Boolean = false,
    version: Option[String] = None
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
    group: Option[String] = None
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
    TargetAnnots(
      AnnotMirror.find[flagged.name, Anns].map(_.value),
      AnnotMirror.find[flagged.help, Anns].map(_.value),
      AnnotMirror.find[flagged.hidden, Anns].isDefined,
      AnnotMirror.find[flagged.version, Anns].map(_.value)
    )

  inline def fieldAnnotsOf[Anns]: FieldAnnots =
    FieldAnnots(
      AnnotMirror.find[flagged.name, Anns].map(_.value),
      AnnotMirror.find[flagged.short, Anns].map(_.value),
      AnnotMirror.find[flagged.help, Anns].map(_.value),
      AnnotMirror.find[flagged.positional, Anns].isDefined,
      AnnotMirror.find[flagged.hidden, Anns].isDefined,
      AnnotMirror.find[flagged.group, Anns].map(_.value)
    )

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
