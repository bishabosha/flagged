package flagged.meta

import language.experimental.captureChecking
import language.experimental.separationChecking

import flagged.internal.{frozen, frozenIArray}

import scala.annotation.Annotation
import scala.compiletime.*
import scala.deriving.Mirror
import scala.annotation.publicInBinary
import scala.NamedTuple.AnyNamedTuple

/** Type-level description of a single annotation occurrence, sparsely encoded: the monomorphic
  * annotation type `A`; `Args`, a named tuple typing only the *explicitly provided* constructor
  * arguments (parameter name -> the argument's constant type, in declaration order); `Arity`, the
  * total number of constructor parameters; and `Indices`, a tuple of the parameter indices of the
  * provided arguments, parallel to `Args`. Omitted parameters (including typer-inserted defaults)
  * are not encoded at all: a consumer that needs a runtime value looks them up through the
  * annotation's [[Defaults]] mirror (with `scala.deriving.Mirror.Of` for the parameter order) at
  * materialisation, and compile-time-only consumers traverse the sparse arguments and assume
  * defaults for the rest. E.g. `@tagged(level = 3)` with `tagged(label: String = "none", level:
  * Int)` mirrors as `Ann[tagged, (level: 3), 2, 1 *: EmptyTuple]`.
  *
  * Purely a phantom type — never instantiated.
  */
sealed trait Ann[A <: Annotation, Args <: AnyNamedTuple, Arity <: Int, Indices <: Tuple]

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
      case _: EmptyTuple                      => None
      case _: (Ann[A, args, arity, idx] *: _) =>
        Some(finish(argsOf[A, args, arity, idx]))
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
      case _: EmptyTuple                      => Nil
      case _: (Ann[A, args, arity, idx] *: t) =>
        finish(argsOf[A, args, arity, idx]) :: findAllImpl[A, t, B](finish)
      case _: (_ *: t) => findAllImpl[A, t, B](finish)

  /** The full constructor-argument tuple for one sparsely mirrored annotation: the explicit
    * constants are materialised from their singleton types into the positions named by `Is`, and
    * every other position is looked up through the annotation's [[Defaults]] mirror (which throws
    * on an index without a default — unreachable for mirrors synthesized by flagged, since an
    * argument is only omitted where the parameter declares a default).
    */
  private inline def argsOf[A: Defaults as d, Args <: AnyNamedTuple, Arity <: Int, Is <: Tuple]
      : Tuple =
    mergeArgs(constValue[Arity], indicesOf[Is], explicitsOf[NamedTuple.DropNames[Args]], d)

  /** Merge explicit arguments (parallel to their `indices` positions) with defaults for the rest.
    */
  @publicInBinary
  private[AnnotMirror] def mergeArgs(
      arity: Int,
      indices: Array[Int],
      explicits: Array[Any],
      d: Defaults[?]
  ): Tuple =
    def merged(): Array[Any] =
      val arr = new Array[Any](arity)
      var i   = 0
      var k   = 0
      while i < arity do
        if k < indices.length && indices(k) == i then
          arr(i) = explicits(k)
          k += 1
        else arr(i) = d.defaultArgument(i)
        i += 1
      arr
    Tuple.fromIArray(frozenIArray(frozen(merged())))

  private inline def indicesOf[Is <: Tuple]: Array[Int] =
    val arr = new Array[Int](constValue[Tuple.Size[Is]])
    writeIndices[Is](arr, 0)
    arr

  private inline def writeIndices[Is <: Tuple](arr: Array[Int], k: Int): Unit =
    inline erasedValue[Is] match
      case _: EmptyTuple => ()
      case _: (i *: it)  =>
        arr(k) = constValue[i & Int]
        writeIndices[it](arr, k + 1)

  private inline def explicitsOf[Vs <: Tuple]: Array[Any] =
    val arr = new Array[Any](constValue[Tuple.Size[Vs]])
    writeExplicits[Vs](arr, 0)
    arr

  private inline def writeExplicits[Vs <: Tuple](arr: Array[Any], k: Int): Unit =
    inline erasedValue[Vs] match
      case _: EmptyTuple => ()
      case _: (v *: vt)  =>
        arr(k) = constOf[v]
        writeExplicits[vt](arr, k + 1)

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
