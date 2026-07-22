package flagged.internal

import flagged.meta.{Ann, MethodMirror, MethodsMirror}
import flagged.meta.MethodsMirror.Entry

/** `T`'s `@run` command tower in one bundle: the object's [[MethodsMirror]], the union of the
  * reachable method result types as `Out`, and one [[MethodResults.EntriesResults]] node per entry,
  * where a scope node holds the nested object's own `MethodResults`. Everything is summoned once,
  * here — consumers take the bundle instead of zipping a mirror with its results at every level.
  *
  * Assembled by ordinary implicit derivation — no macros, no inline. A method's contribution to
  * `Out` is computed by match types ([[MethodResults.MethodContrib]], via the alias-pattern
  * extractors on [[MethodMirror]]); instances are typed by their singleton types so no refinement
  * is lost. Non-`@run` members contribute `Nothing` — the parser never invokes them.
  */
sealed trait MethodResults[T]:
  type Out

  /** The precise type of [[mirror]]. */
  type Mirror <: MethodsMirror[T]

  /** The precise node type of [[entries]]: consumers keep every refinement without re-summoning. */
  type Entries <: MethodResults.EntriesResults[?]

  /** The object's mirror, summoned once during derivation. */
  val mirror: Mirror

  /** The derivation tower behind `Out`. */
  val entries: Entries

object MethodResults:
  final class Impl[T, O, M <: MethodsMirror[T], E <: EntriesResults[?]](
      val mirror: M,
      val entries: E
  ) extends MethodResults[T]:
    type Out     = O
    type Mirror  = M
    type Entries = E

  given of: [T] => (mm: MethodsMirror[T]) => (er: EntriesResults[mm.MirroredEntries])
    => (
      Impl[T, er.Out, mm.type, er.type]
    ) =
    Impl[T, er.Out, mm.type, er.type](mm, er)

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

    final class MethodNode[S, M <: MethodMirror[S], Ts <: Tuple, RE <: EntriesResults[Ts]](
        val rest: RE
    ) extends EntriesResults[Entry.Method[M] *: Ts]:
      type Out = MethodContrib[M] | rest.Out

    /** Holds the scope's whole nested tower — mirror included, via `results.mirror`; `SR` is its
      * singleton type, so the walk in [[DeriveMethods]] recovers the full refinements from the node
      * type alone.
      */
    final class ScopeNode[S, Ts <: Tuple, SR <: MethodResults[S], RE <: EntriesResults[Ts], O](
        val results: SR,
        val rest: RE
    ) extends EntriesResults[Entry.Scope[S] *: Ts]:
      type Out = O

    given empty: Empty = Empty()

    given method: [S, M <: MethodMirror[S], Ts <: Tuple] => (rest: EntriesResults[Ts])
      => (
        MethodNode[S, M, Ts, rest.type]
      ) =
      MethodNode(rest)

    given scope: [S, Ts <: Tuple] => (sr: MethodResults[S], rest: EntriesResults[Ts])
      => (
        ScopeNode[
          S,
          Ts,
          sr.type,
          rest.type,
          IfRun[sr.mirror.MirroredSelfAnnotations, sr.Out] | rest.Out
        ]
      ) =
      ScopeNode(sr, rest)
