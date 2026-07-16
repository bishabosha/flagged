package claw.internal

import scala.annotation.Annotation
import scala.compiletime.*
import scala.deriving.Mirror

/** Type-level description of a single annotation occurrence: the monomorphic
  * annotation type `A`, the singleton types of its constructor arguments `Args`, and
  * a parallel tuple `Defaulted` of boolean literal types. Where `Defaulted` is
  * `false`, the `Args` element is the provided argument's constant type; where it is
  * `true`, the argument was omitted (or a typer-inserted default) and the `Args`
  * element is the parameter's index, to be looked up through the annotation's
  * [[Defaults]] mirror at materialisation. E.g. `@tagged(level = 3)` with
  * `tagged(label: String = "none", level: Int)` mirrors as
  * `Ann[tagged, (0, 3), (true, false)]`.
  *
  * Purely a phantom type — never instantiated.
  */
sealed trait Ann[A <: Annotation, Args <: Tuple, Defaulted <: Tuple]

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
    * `Ann` is invariant, so each head either is an `Ann[A, _, _]` or provably is not.
    *
    * The annotation's [[Defaults]] mirror is a context parameter: pin one in the
    * annotation's companion to share a single instance across all `find` sites
    * (otherwise the fallback `Defaults.of` given synthesizes one per summon).
    */
  inline def find[A <: Annotation: {Mirror.ProductOf as m, Defaults as d}, Anns]: Option[A] =
    inline erasedValue[Anns] match
      case _: EmptyTuple => None
      case _: (Ann[A, args, defaulted] *: _) =>
        Some(m.fromProduct(argsOf[A, args, defaulted]))
      case _: (_ *: t) => find[A, t]

  /** The constructor-argument tuple for one mirrored annotation: provided constants
    * are materialised directly; defaulted positions are looked up through the
    * annotation's [[Defaults]] mirror.
    */
  inline def argsOf[A, Args, Defaulted](using d: Defaults[A]): Tuple =
    resolve[Args, Defaulted](i =>
      d.values
        .lift(i)
        .flatten
        .fold(throw new IllegalStateException(s"no default for annotation parameter $i"))(_())
    )

  inline def resolve[Args, D](lookup: Int => Any): Tuple =
    inline erasedValue[Args] match
      case _: EmptyTuple => EmptyTuple
      case _: (ah *: at) =>
        inline erasedValue[D] match
          case _: (dh *: dt) =>
            val head: Any = inline erasedValue[dh] match
              case _: false => constValue[ah]
              case _: true  => lookup(constValue[ah].asInstanceOf[Int])
            head *: resolve[at, dt](lookup)
