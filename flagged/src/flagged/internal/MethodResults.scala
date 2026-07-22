package flagged.internal

import flagged.meta.{Ann, MethodMirror, MethodsMirror}
import flagged.meta.MethodsMirror.Entry

/** The union of the result types of `T`'s `@run` command tower, carried as the `Out` member.
  *
  * Assembled by ordinary implicit derivation — no macros, no inline:
  * [[MethodResults.EntriesResults]] folds one mirror's entry tags, where a method's contribution is
  * computed by match types ([[MethodResults.MethodContrib]], via the alias-pattern extractors on
  * [[MethodMirror]]) and a nested [[Entry.Scope]] recursively summons the nested object's own
  * tower. Non-`@run` members contribute `Nothing` — the parser never invokes them.
  */
sealed trait MethodResults[T]:
  type Out

object MethodResults:
  /** Carrier: the structure is purely type-level, one class serves every instance. */
  final class Impl[T, O] extends MethodResults[T]:
    type Out = O

  given of: [T] => (mm: MethodsMirror[T]) => (er: EntriesResults[mm.MirroredEntries])
    => (
      MethodResults[T] { type Out = er.Out }
    ) =
    Impl[T, er.Out]()

  /** Is `flagged.run` itself among the [[Ann]]-encoded annotations? Other
    * [[flagged.meta.Reflectable]] markers do not count.
    */
  type HasRun[Anns <: Tuple] <: Boolean = Anns match
    case EmptyTuple                  => false
    case Ann[flagged.run, ?, ?] *: ? => true
    case ? *: t                      => HasRun[t]

  /** `R` when the annotations carry `@run`, `Nothing` otherwise. */
  type IfRun[Anns <: Tuple, R] = HasRun[Anns] match
    case true  => R
    case false => Nothing

  /** An [[Entry.Method]] tag's contribution to the union: the method's result if it is `@run`. */
  type MethodContrib[M] = M match
    case MethodMirror.WithAnnots[anns] => IfRun[anns, MethodMirror.ResultOf[M]]

  /** The fold over one mirror's entry tags. */
  sealed trait EntriesResults[Es <: Tuple]:
    type Out

  object EntriesResults:
    final class Impl[Es <: Tuple, O] extends EntriesResults[Es]:
      type Out = O

    given empty: (EntriesResults[EmptyTuple] { type Out = Nothing }) =
      Impl[EmptyTuple, Nothing]()

    given method: [m, t <: Tuple] => (rest: EntriesResults[t])
      => (
        EntriesResults[Entry.Method[m] *: t] { type Out = MethodContrib[m] | rest.Out }
      ) =
      Impl[Entry.Method[m] *: t, MethodContrib[m] | rest.Out]()

    given scope: [s, t <: Tuple] => (
        sm: MethodsMirror[s],
        sr: MethodResults[s],
        rest: EntriesResults[t]
    )
      => (
        EntriesResults[Entry.Scope[s] *: t] {
          type Out = IfRun[sm.MirroredSelfAnnotations, sr.Out] | rest.Out
        }
      ) =
      Impl[Entry.Scope[s] *: t, IfRun[sm.MirroredSelfAnnotations, sr.Out] | rest.Out]()
