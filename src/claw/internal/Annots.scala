package claw.internal

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
