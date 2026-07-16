package claw.internal

import scala.annotation.Annotation
import scala.compiletime.*
import scala.deriving.Mirror

/** Type-level description of a single annotation occurrence: the monomorphic
  * annotation type `A` and the singleton types of its constructor arguments `Args`
  * (e.g. `Ann[claw.short, 'v' *: EmptyTuple]`). Purely a phantom type — never
  * instantiated; subsequent code materialises the singleton types into a real value.
  */
sealed trait Ann[A <: Annotation, Args <: Tuple]

/** `Mirror`-style witness describing how `T` is annotated. Like `Mirror`, all
  * information lives in type members, so a compiler-intrinsic version of this would
  * synthesize only types. The kind — [[AnnotMirror.Product]] for classes,
  * [[AnnotMirror.Sum]] for enums / sealed traits — determines how
  * `MirroredAnnotations` is interpreted: one slot per constructor field, or one slot
  * per case.
  *
  * Only annotations that are case classes extending `StaticAnnotation` and applied
  * with compile-time-constant arguments are mirrored — the same restriction a
  * compiler intrinsic would need so that every argument has a singleton type and the
  * annotation can be rebuilt through its `Mirror.ProductOf`.
  */
sealed trait AnnotMirror[T]:
  /** Tuple of [[Ann]] types: the annotations on `T` itself. */
  type MirroredSelfAnnotations <: Tuple

  /** Tuple with one element per member — constructor fields for products, cases for
    * sums; each element is a tuple of [[Ann]] types.
    */
  type MirroredAnnotations <: Tuple

object AnnotMirror:

  trait Product[T] extends AnnotMirror[T]

  trait Sum[T] extends AnnotMirror[T]

  /** Synthesize the annotation mirror for a class (macro-backed; the intrinsic
    * candidate). Given as `transparent inline` so the refined type members reach the
    * summoning site — like `Mirror`, the refinement only survives deferred summoning
    * (`summonFrom`/`summonInline`/`using`), not direct calls inside inline bodies.
    */
  transparent inline given ofProduct[T]: AnnotMirror.Product[T] =
    ${ MetaMacros.annotMirrorProduct[T] }

  /** Synthesize the annotation mirror for an enum / sealed trait. */
  transparent inline given ofSum[T]: AnnotMirror.Sum[T] =
    ${ MetaMacros.annotMirrorSum[T] }

  /** The first annotation of type `A` in slot `Anns`, materialised — fully resolved
    * at compile time, so consumers get a typed `Option[A]` with no runtime type test.
    * `A` is matched directly in the head pattern: it is concrete at expansion and
    * `Ann` is invariant, so each head either is an `Ann[A, _]` or provably is not.
    */
  inline def find[A <: Annotation: Mirror.ProductOf as m, Anns]: Option[A] =
    inline erasedValue[Anns] match
      case _: EmptyTuple => None
      case _: (Ann[A, args] *: _) =>
        Some(m.fromProduct(constValueTuple[args]))
      case _: (_ *: t) => find[A, t]

