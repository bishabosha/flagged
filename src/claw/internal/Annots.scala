package claw.internal

import compiletime.{summonFrom, erasedValue}
import claw.meta.AnnotMirror

/** claw's annotations on a type or an enum case, extracted at compile time from an [[AnnotMirror]]
  * — fully typed, no `Any` and no runtime type tests.
  */
final case class TargetAnnots(name: Option[String], help: Option[String])

object TargetAnnots:
  val empty: TargetAnnots = TargetAnnots(None, None)

/** claw's annotations on one constructor field, extracted at compile time. */
final case class FieldAnnots(
    name: Option[String],
    short: Option[Char],
    help: Option[String],
    positional: Boolean
)

object FieldAnnots:
  val empty: FieldAnnots = FieldAnnots(None, None, None, false)

/** Runtime carrier for extracted annotations, built by `Derive.productAnnots` / `Derive.sumAnnots`.
  * Shaped like the type they describe: products carry per-field slots, sums per-case slots.
  */
enum Annots[A]:
  /** Annotations of a case class: on the type itself and per constructor field. */
  case Product[T](onType: TargetAnnots, perField: List[FieldAnnots]) extends Annots[T]

  /** Annotations of an enum / sealed trait: on the type itself and per case. */
  case Sum[T](onType: TargetAnnots, perCase: List[TargetAnnots]) extends Annots[T]

  def onType: TargetAnnots

object Annots:

  /** Extract claw's annotations for a product into typed records. */
  inline def productAnnots[A]: Annots.Product[A] =
    summonFrom:
      case am: AnnotMirror.Product[A] =>
        Annots.Product[A](
          targetAnnotsOf[am.MirroredSelfAnnotations],
          fieldAnnotsEach[am.MirroredAnnotations]
        )

  /** Extract claw's annotations for a sum into typed records. */
  inline def sumAnnots[A]: Annots.Sum[A] =
    summonFrom:
      case am: AnnotMirror.Sum[A] =>
        Annots.Sum[A](
          targetAnnotsOf[am.MirroredSelfAnnotations],
          targetAnnotsEach[am.MirroredAnnotations]
        )

  inline def targetAnnotsOf[Anns]: TargetAnnots =
    TargetAnnots(
      AnnotMirror.find[claw.name, Anns].map(_.value),
      AnnotMirror.find[claw.help, Anns].map(_.value)
    )

  inline def fieldAnnotsOf[Anns]: FieldAnnots =
    FieldAnnots(
      AnnotMirror.find[claw.name, Anns].map(_.value),
      AnnotMirror.find[claw.short, Anns].map(_.value),
      AnnotMirror.find[claw.help, Anns].map(_.value),
      AnnotMirror.find[claw.positional, Anns].isDefined
    )

  inline def fieldAnnotsEach[Slots]: List[FieldAnnots] =
    inline erasedValue[Slots] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => fieldAnnotsOf[h] :: fieldAnnotsEach[t]

  inline def targetAnnotsEach[Slots]: List[TargetAnnots] =
    inline erasedValue[Slots] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => targetAnnotsOf[h] :: targetAnnotsEach[t]
