package flagged.meta

import language.experimental.captureChecking
import language.experimental.separationChecking

import flagged.internal.{frozen, frozenIArray}

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
          finish(argsOf[A, m.MirroredElemTypes, args, defaulted])
        )
      case _: (_ *: t) => findImpl[A, t, B](finish)

  /** All slots in `Anns` that match type `A`, in declaration order, materialised as named tuples
    * (for repeatable annotations). Default arguments are filled in.
    */
  inline def findAll[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns]
      : List[NamedTuple.From[A]] =
    findAllImpl[A, Anns, NamedTuple.From[A]]: args =>
      NamedTuple(args).asInstanceOf[NamedTuple.From[A]]

  private inline def findAllImpl[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns, B](
      inline finish: Tuple => B
  ): List[B] =
    inline erasedValue[Anns] match
      case _: EmptyTuple                     => Nil
      case _: (Ann[A, args, defaulted] *: t) =>
        finish(argsOf[A, m.MirroredElemTypes, args, defaulted]) :: findAllImpl[A, t, B](finish)
      case _: (_ *: t) => findAllImpl[A, t, B](finish)

  /** The constructor-argument tuple for one mirrored annotation: provided constants are
    * materialised directly; defaulted positions are looked up through the annotation's [[Defaults]]
    * mirror (which throws on an index without a default — unreachable for mirrors synthesized by
    * flagged).
    */
  private inline def argsOf[A: Defaults as d, Elems <: Tuple, Args <: Tuple, Defaulted <: Tuple]
      : Tuple =
    inline erasedValue[Elems] match
      case _: EmptyTuple => EmptyTuple
      case _             =>
        inline erasedValue[(Elems, Args, Defaulted)] match
          case _: (ex *: EmptyTuple, ax *: EmptyTuple, dx *: EmptyTuple) =>
            Tuple1(resolveInner[ex, ax, dx](d.defaultArgument))
          case _: (e1 *: e2 *: EmptyTuple, a1 *: a2 *: EmptyTuple, d1 *: d2 *: EmptyTuple) =>
            Tuple2(
              resolveInner[e1, a1, d1](d.defaultArgument),
              resolveInner[e2, a2, d2](d.defaultArgument)
            )
          case _: (
                  e1 *: e2 *: e3 *: EmptyTuple,
                  a1 *: a2 *: a3 *: EmptyTuple,
                  d1 *: d2 *: d3 *: EmptyTuple
              ) =>
            Tuple3(
              resolveInner[e1, a1, d1](d.defaultArgument),
              resolveInner[e2, a2, d2](d.defaultArgument),
              resolveInner[e3, a3, d3](d.defaultArgument)
            )
          case _ =>
            buildTuple(constValue[Tuple.Size[Elems]])({ append =>
              resolveMany[Elems, Args, Defaulted](append, d.defaultArgument)
            })

  @publicInBinary
  private[AnnotMirror] def buildTuple[T, A](size: Int)(
      build: (append: Any => Unit) => Unit
  ): Tuple =
    val buf = new scala.collection.mutable.ArrayBuffer[AnyRef](size)
    build(x => buf += x.asInstanceOf[AnyRef])
    Tuple.fromIArray(frozenIArray(frozen(buf.toArray)))

  private inline def resolveMany[Elems, Args, D](
      inline append: Any => Unit,
      inline lookup: Int => Any
  ): Unit =
    inline erasedValue[Elems] match
      case _: (eh *: et) =>
        inline erasedValue[Args] match
          case _: (ah *: at) =>
            inline erasedValue[D] match
              case _: (dh *: dt) =>
                append(resolveInner[eh, ah, dh](lookup))
                resolveMany[et, at, dt](append, lookup)
      case _: EmptyTuple => ()

  private inline def resolveInner[Eh, Ah, Dh](
      inline lookup: Int => Any
  ): Eh =
    inline erasedValue[Dh] match
      case _: false => constValue[Ah].asInstanceOf[Eh]
      case _: true  => lookup(constValue[Ah].asInstanceOf[Int]).asInstanceOf[Eh]
