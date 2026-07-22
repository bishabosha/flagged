package flagged.internal

import scala.compiletime.*
import flagged.meta.{MethodMirror, MethodsMirror}
import flagged.meta.MethodsMirror.Entry

/** The union of the result types of `T`'s `@run` command tower, carried as the `Out` member.
  *
  * The mirror itself is one level deep and result-agnostic, so the union is assembled here: one
  * level's method results are read off the [[Entry.Method]] tags, and each [[Entry.Scope]] is
  * descended by summoning the nested object's own [[MethodsMirror]] and splicing its entries into
  * the fold. Non-`@run` members are excluded — the parser never invokes them.
  */
sealed trait MethodResults[T]:
  type Out

object MethodResults:
  /** Carrier: named so transparent expansion shares one class. */
  final class Impl[T, O] extends MethodResults[T]:
    type Out = O

  transparent inline given of[T](using mm: MethodsMirror[T]): MethodResults[T] =
    build[T, mm.MirroredEntries, Nothing]

  /** Fold the entry tags into the union accumulator `Acc`. The result type rides on the tag
    * (`Entry.Method[m, r]`): a match binder is a concrete type, where extracting the mirror's
    * `MirroredResult` member through a term binder would leak that binder into `Out`.
    */
  private transparent inline def build[T, Es <: Tuple, Acc]: MethodResults[T] =
    inline erasedValue[Es] match
      case _: EmptyTuple                => Impl[T, Acc]()
      case _: (Entry.Method[m, r] *: t) =>
        inline if DeriveMethods.methodIsRun[m] then build[T, t, Acc | r]
        else build[T, t, Acc]
      case _: (Entry.Scope[s] *: t) => scopeFold[T, s, t, Acc]

  /** Splice a nested scope's entries into the same fold. Summoning the scope's own mirror keeps the
    * recursion on `MethodsMirror` — a recursive `MethodResults` summon would trip the implicit
    * divergence checker.
    */
  private transparent inline def scopeFold[T, S, Rest <: Tuple, Acc]: MethodResults[T] =
    inline if DeriveMethods.scopeIsRun[S] then
      summonFrom:
        case sm: MethodsMirror[S] =>
          // destructure the nested entries first: the inline-match binders are concrete types,
          // so the Concat over them reduces (a path-dependent argument would not)
          inline erasedValue[sm.MirroredEntries] match
            case _: EmptyTuple => build[T, Rest, Acc]
            case _: (h *: t)   => build[T, h *: Tuple.Concat[t, Rest], Acc]
    else build[T, Rest, Acc]
