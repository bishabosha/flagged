package flagged.internal

import flagged.meta.{Ann, MethodMirror, MethodsMirror}
import flagged.meta.MethodsMirror.Entry

/** The union of the result types of `T`'s `@run` command tower, carried as the `Out` member, plus
  * the tower itself: [[entries]] bundles every nested scope's summoned [[MethodsMirror]] (and its
  * own `MethodResults`), so group derivation reuses the instances instead of repeating the implicit
  * searches.
  *
  * Assembled by ordinary implicit derivation — no macros, no inline:
  * [[MethodResults.EntriesResults]] folds one mirror's entry tags by given induction. A method's
  * contribution to `Out` is computed by match types ([[MethodResults.MethodContrib]], via the
  * alias-pattern extractors on [[MethodMirror]]); an [[Entry.Scope]] summons the nested object's
  * mirror and tower once and stores them in the node, typed by their singleton types so no
  * refinement is lost. Non-`@run` members contribute `Nothing` — the parser never invokes them.
  */
sealed trait MethodResults[T]:
  type Out

  /** The precise node type of [[entries]]: consumers keep every refinement without re-summoning. */
  type Entries <: MethodResults.EntriesResults[?]

  /** The derivation tower behind `Out`. */
  def entries: Entries

object MethodResults:
  final class Impl[T, O, E <: EntriesResults[?]](val entries: E) extends MethodResults[T]:
    type Out     = O
    type Entries = E

  given of: [T] => (mm: MethodsMirror[T]) => (er: EntriesResults[mm.MirroredEntries])
    => (
      Impl[T, er.Out, er.type]
    ) =
    Impl[T, er.Out, er.type](er)

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

  /** The fold over one mirror's entry tags: one node per entry, in order. */
  sealed trait EntriesResults[Es <: Tuple]:
    type Out

  object EntriesResults:
    final class Empty extends EntriesResults[EmptyTuple]:
      type Out = Nothing

    final class MethodNode[m, t <: Tuple, RE <: EntriesResults[t]](val rest: RE)
        extends EntriesResults[Entry.Method[m] *: t]:
      type Out = MethodContrib[m] | rest.Out

    /** Stores the scope's summoned mirror and tower; `SM`/`SR` are their singleton types, so the
      * walk in [[DeriveMethods]] recovers the full refinements from the node type alone.
      */
    final class ScopeNode[
        s,
        t <: Tuple,
        SM <: MethodsMirror[s],
        SR <: MethodResults[s],
        RE <: EntriesResults[t],
        O
    ](
        val mirror: SM,
        val results: SR,
        val rest: RE
    ) extends EntriesResults[Entry.Scope[s] *: t]:
      type Out = O

    given empty: Empty = Empty()

    given method: [m, t <: Tuple] => (rest: EntriesResults[t])
      => (
        MethodNode[m, t, rest.type]
      ) =
      MethodNode(rest)

    given scope: [s, t <: Tuple] => (
        sm: MethodsMirror[s],
        sr: MethodResults[s],
        rest: EntriesResults[t]
    )
      => (
        ScopeNode[
          s,
          t,
          sm.type,
          sr.type,
          rest.type,
          IfRun[sm.MirroredSelfAnnotations, sr.Out] | rest.Out
        ]
      ) =
      ScopeNode(sm, sr, rest)
