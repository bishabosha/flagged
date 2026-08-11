package flagged.meta

import language.experimental.captureChecking
import language.experimental.separationChecking

import flagged.internal.{frozen, frozenIArray}

import scala.annotation.Annotation
import scala.compiletime.*
import scala.compiletime.ops.int.S
import scala.deriving.Mirror
import scala.annotation.publicInBinary

/** Type-level description of a single annotation occurrence, sparsely encoded: the monomorphic
  * annotation type `A`, and three parallel tuples typing only the *explicitly provided* constructor
  * arguments, in declaration order: their parameter `Names`, their constant `Values`, and their
  * parameter `Indices`. Omitted parameters (including typer-inserted defaults) are not encoded at
  * all: a consumer that needs a runtime value looks them up through the annotation's [[Defaults]]
  * mirror (with `scala.deriving.Mirror.Of` for the parameter order and count), and
  * compile-time-only consumers traverse the sparse columns and assume defaults for the rest. E.g.
  * `@tagged(level = 3)` with `tagged(label: String = "none", level: Int)` mirrors as
  * `Ann[tagged, "level" *: EmptyTuple, 3 *: EmptyTuple, 1 *: EmptyTuple]`.
  *
  * Plain parameters rather than a named tuple or `Mirror`-style type members: consumers are
  * match-type and inline-match walks, and both alternatives measurably tax them — a named tuple
  * adds a `Names`/`DropNames` reduction per query, and type members can neither be pattern-matched
  * for dispatch (no member disjointness) nor extracted without an alias-pattern reduction per
  * column.
  *
  * Purely a phantom type — never instantiated.
  */
sealed trait Ann[A <: Annotation, Names <: Tuple, Values <: Tuple, Indices <: Tuple]

/** `Mirror`-style witness describing how `T` is annotated. Like `Mirror`, all information lives in
  * type members, so a compiler-intrinsic version of this would synthesize only types. The kind —
  * [[AnnotMirror.Product]] for classes, [[AnnotMirror.Sum]] for enums / sealed traits — determines
  * how `MirroredAnnotations` is interpreted: one slot per constructor field, or one slot per case.
  *
  * Only annotations that are case classes extending `StaticAnnotation` and applied with
  * compile-time-constant arguments (single constants, or tuples of constants such as an `aliases`
  * list) are mirrored — the same restriction a compiler intrinsic would need so that every provided
  * argument has a singleton type and the annotation can be rebuilt through its `Mirror.ProductOf`.
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

  object Product {
    object Empty extends Product[Any]:
      type MirroredSelfAnnotations = EmptyTuple
      type MirroredAnnotations     = EmptyTuple
  }

  trait Sum[T] extends AnnotMirror[T]

  object Sum {
    object Empty extends Sum[Any]:
      type MirroredSelfAnnotations = EmptyTuple
      type MirroredAnnotations     = EmptyTuple
  }


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

  /** Find first slot in `Anns` that matches type `A` and materialise its arguments as `A`. Omitted
    * arguments are filled in through the [[Defaults]] mirror.
    */
  inline def findExact[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns]: Option[A] =
    findImpl[A, Anns, A](m.fromProduct)

  /** Find first slot in `Anns` that matches type `A` and materialise its arguments as a named
    * tuple. Omitted arguments are filled in through the [[Defaults]] mirror.
    */
  inline def find[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns]
      : Option[NamedTuple.From[A]] =
    findImpl[A, Anns, NamedTuple.From[A]]: args =>
      NamedTuple(args).asInstanceOf[NamedTuple.From[A]]

  private inline def findImpl[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns, B](
      inline finish: Tuple => B
  ): Option[B] =
    inline erasedValue[Anns] match
      case _: EmptyTuple                 => None
      case _: (Ann[A, ns, vs, idx] *: _) =>
        Some(finish(argsOf[A, m.MirroredElemTypes, vs, idx]))
      case _: (_ *: t) => findImpl[A, t, B](finish)

  /** All slots in `Anns` that match type `A`, in declaration order, materialised as named tuples
    * (for repeatable annotations). Omitted arguments are filled in.
    */
  inline def findAll[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns]
      : List[NamedTuple.From[A]] =
    findAllImpl[A, Anns, NamedTuple.From[A]]: args =>
      NamedTuple(args).asInstanceOf[NamedTuple.From[A]]

  private inline def findAllImpl[A <: Annotation: {Mirror.ProductOf as m, Defaults}, Anns, B](
      inline finish: Tuple => B
  ): List[B] =
    inline erasedValue[Anns] match
      case _: EmptyTuple                 => Nil
      case _: (Ann[A, ns, vs, idx] *: t) =>
        finish(argsOf[A, m.MirroredElemTypes, vs, idx]) :: findAllImpl[A, t, B](finish)
      case _: (_ *: t) => findAllImpl[A, t, B](finish)

  /** The full constructor-argument tuple for one sparsely mirrored annotation: the explicit
    * constants are materialised from their singleton types into the positions named by `Is`, and
    * every other position comes from the annotation's [[Defaults]] mirror.
    */
  private inline def argsOf[A: Defaults as d, Elems <: Tuple, Vs <: Tuple, Is <: Tuple]: Tuple =
    argsTuple(constValue[Tuple.Size[Elems]]): arr =>
      writeArgs[0, Tuple.Size[Elems], Vs, Is](arr, d)

  /** Freeze the argument array the inline walk writes. */
  @publicInBinary
  private[AnnotMirror] def argsTuple(arity: Int)(write: Array[AnyRef]^ => Unit): Tuple =
    def filled(): Array[AnyRef]^ =
      val arr = new Array[AnyRef](arity)
      write(arr)
      arr
    Tuple.fromIArray(frozenIArray(frozen(filled())))

  /** One interwoven walk over the positions `I until N`: a position at the head of the explicit
    * `Is` column takes its constant from `Vs`, and any other position pulls its default — the
    * [[Defaults]] mirror is consulted for exactly the omitted parameters.
    */
  private inline def writeArgs[I <: Int, N <: Int, Vs <: Tuple, Is <: Tuple](
      arr: Array[AnyRef]^,
      d: Defaults[?]
  ): Unit =
    inline if constValue[I] == constValue[N] then ()
    else
      inline erasedValue[(Vs, Is)] match
        case _: (v *: vt, ih *: it) =>
          inline if constValue[I] == constValue[ih & Int] then
            arr(constValue[I]) = constOf[v].asInstanceOf[AnyRef]
            writeArgs[S[I], N, vt, it](arr, d)
          else
            writeDefaultAt[I](arr, d)
            writeArgs[S[I], N, Vs, Is](arr, d)
        case _ =>
          writeDefaultAt[I](arr, d)
          writeArgs[S[I], N, Vs, Is](arr, d)

  private inline def writeDefaultAt[I <: Int](arr: Array[AnyRef]^, d: Defaults[?]): Unit =
    arr(constValue[I]) = d.defaultArgument(constValue[I]).asInstanceOf[AnyRef]

  /** One explicit argument materialised from its constant type: a plain literal via `constValue`, a
    * tuple of constants (e.g. an `aliases` argument) element-wise.
    */
  private inline def constOf[V]: Any =
    inline erasedValue[V] match
      case _: EmptyTuple => EmptyTuple
      case _: (h *: t)   => constTuple[h *: t]
      case _             => constValue[V]

  private inline def constTuple[T <: Tuple]: Tuple =
    inline erasedValue[T] match
      case _: EmptyTuple => EmptyTuple
      case _: (h *: t)   => constOf[h] *: constTuple[t]
