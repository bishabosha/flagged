package flagged.meta

import scala.annotation.Annotation
import scala.compiletime.*
import scala.deriving.Mirror
import scala.annotation.publicInBinary

/** Type-level description of a single annotation occurrence: the monomorphic annotation type `A`,
  * the singleton types of its constructor arguments `Args`, and a parallel tuple `Defaulted` of
  * boolean literal types. Where `Defaulted` is `false`, the `Args` element is the provided
  * argument's constant type; where it is `true`, the argument was omitted (or a typer-inserted
  * default) and the `Args` element is the parameter's index, to be looked up through the
  * annotation's [[Defaults]] mirror at materialisation. E.g. `@tagged(level = 3)` with
  * `tagged(label: String = "none", level: Int)` mirrors as `Ann[tagged, (0, 3), (true, false)]`.
  *
  * Purely a phantom type — never instantiated.
  */
sealed trait Ann[A <: Annotation, Args <: Tuple, Defaulted <: Tuple]

/** `Mirror`-style witness describing how `T` is annotated. Like `Mirror`, all information lives in
  * type members, so a compiler-intrinsic version of this would synthesize only types. The kind —
  * [[AnnotMirror.Product]] for classes, [[AnnotMirror.Sum]] for enums / sealed traits — determines
  * how `MirroredAnnotations` is interpreted: one slot per constructor field, or one slot per case.
  *
  * Only annotations that are case classes extending `StaticAnnotation` and applied with
  * compile-time-constant arguments are mirrored — the same restriction a compiler intrinsic would
  * need so that every argument has a singleton type and the annotation can be rebuilt through its
  * `Mirror.ProductOf`.
  */
sealed trait AnnotMirror[T]:
  /** Tuple of [[Ann]] types: the annotations on `T` itself. */
  type MirroredSelfAnnotations <: Tuple

  /** Tuple with one element per member — constructor fields for products, cases for sums; each
    * element is a tuple of [[Ann]] types.
    */
  type MirroredAnnotations <: Tuple

object AnnotMirror:

  trait Product[T] extends AnnotMirror[T]

  trait Sum[T] extends AnnotMirror[T]

  /** Synthesize the annotation mirror for a class (macro-backed; the intrinsic candidate). Given as
    * `transparent inline` so the refined type members reach the summoning site — like `Mirror`, the
    * refinement only survives deferred summoning (`summonFrom`/`summonInline`/`using`), not direct
    * calls inside inline bodies.
    */
  transparent inline given ofProduct[T]: AnnotMirror.Product[T] =
    ${ macros.AnnotationMacros.annotMirrorProduct[T] }

  /** Synthesize the annotation mirror for an enum / sealed trait. */
  transparent inline given ofSum[T]: AnnotMirror.Sum[T] =
    ${ macros.AnnotationMacros.annotMirrorSum[T] }

  /** Find first slot in `Anns` that matches type `A` and materialise its arguments as `A`. Default
    * arguments are filled in.
    */
  inline def findExact[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns]: Option[A] =
    findImpl[A, Anns, A](m.fromProduct)

  /** Find first slot in `Anns` that matches type `A` and materialise its arguments as a named
    * tuple. Default arguments are filled in.
    */
  inline def find[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns]
      : Option[NamedTuple.From[A]] =
    findImpl[A, Anns, NamedTuple.From[A]]: args =>
      NamedTuple(args).asInstanceOf[NamedTuple.From[A]]

  private inline def findImpl[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns, B](
      inline finish: Tuple => B
  ): Option[B] =
    inline erasedValue[Anns] match
      case _: EmptyTuple                     => None
      case _: (Ann[A, args, defaulted] *: _) =>
        Some(
          finish(argsOf[A, args, defaulted, Tuple.Size[m.MirroredElemTypes]])
        )
      case _: (_ *: t) => findImpl[A, t, B](finish)

  /** The constructor-argument tuple for one mirrored annotation: provided constants are
    * materialised directly; defaulted positions are looked up through the annotation's [[Defaults]]
    * mirror (which throws on an index without a default — unreachable for mirrors synthesized by
    * flagged).
    */
  private inline def argsOf[A: Defaults as d, Args, Defaulted, Size <: Int]: Tuple =
    buildTuple(constValue[Size])({ append =>
      resolve[Args, Defaulted](append, d.defaultArgument)
    })

  @publicInBinary
  private[AnnotMirror] def buildTuple[T, A](size: Int)(
      build: (append: Any => Unit) => Unit
  ): Tuple =
    val buf     = new Array[AnyRef](size)
    val indexer = new (Any => Unit) {
      var i                   = 0
      def apply(x: Any): Unit =
        buf(i) = x.asInstanceOf[AnyRef]
        i += 1
    }
    build(indexer)
    Tuple.fromIArray(IArray.unsafeFromArray(buf))

  private inline def resolve[Args, D](inline append: Any => Unit, inline lookup: Int => Any): Unit =
    inline erasedValue[Args] match
      case _: EmptyTuple => ()
      case _: (ah *: at) =>
        inline erasedValue[D] match
          case _: (dh *: dt) =>
            val next: Any = inline erasedValue[dh] match
              case _: false => constValue[ah]
              case _: true  => lookup(constValue[ah].asInstanceOf[Int])
            append(next)
            resolve[at, dt](append, lookup)
