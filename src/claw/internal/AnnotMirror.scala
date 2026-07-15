package claw.internal

import scala.annotation.{Annotation, StaticAnnotation}
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
  * synthesize only types. Two kinds: [[AnnotMirror.Product]] for classes (annotations
  * on the class itself plus one slot per constructor field) and [[AnnotMirror.Sum]]
  * for enums / sealed traits (one slot per case).
  *
  * Only annotations that are case classes extending `StaticAnnotation` and applied
  * with compile-time-constant arguments are mirrored — the same restriction a
  * compiler intrinsic would need so that every argument has a singleton type and the
  * annotation can be rebuilt through its `Mirror.ProductOf`.
  */
sealed trait AnnotMirror[T]:
  /** Tuple of [[Ann]] types: the annotations on `T` itself. */
  type MirroredAnnotations <: Tuple

object AnnotMirror:

  trait Product[T] extends AnnotMirror[T]:
    /** Tuple with one element per constructor field; each element is a tuple of [[Ann]] types. */
    type MirroredFieldAnnotations <: Tuple

  trait Sum[T] extends AnnotMirror[T]:
    /** Tuple with one element per case; each element is a tuple of [[Ann]] types. */
    type MirroredCaseAnnotations <: Tuple

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

  /** Materialise one slot: rebuild each annotation value from its mirrored
    * constructor-argument singleton types via the annotation's own `Mirror`.
    * Plain binders are safe here: annotation arguments are literal constant types,
    * which — unlike term singletons — survive inline-match binders.
    */
  inline def materialize[Anns]: List[Any] =
    inline erasedValue[Anns] match
      case _: EmptyTuple => Nil
      case _: (Ann[a, args] *: t) =>
        summonInline[Mirror.ProductOf[a]].fromProduct(constValueTuple[args]) :: materialize[t]

  /** Materialise a tuple of slots (per-field or per-case). */
  inline def materializeEach[Slots]: List[List[Any]] =
    inline erasedValue[Slots] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => materialize[h] :: materializeEach[t]
