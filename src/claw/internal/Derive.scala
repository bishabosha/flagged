package claw.internal

import scala.compiletime.*
import scala.deriving.Mirror
import claw.{Parser, Reader}

/** `Mirror`-based derivation. Structure and construction come from `Mirror`; nested
  * `Parser` and `Reader` instances are summoned before falling back to derivation, so
  * user-supplied instances for any level of the command tree are respected. The only
  * macro-backed pieces are [[Defaults]] (term-level: default values are arbitrary
  * expressions) and [[AnnotMirror]] (type-level: annotations reduced to singleton
  * types, materialised here via [[productAnnots]]/[[sumAnnots]]).
  */
object Derive:

  /** Materialise the [[AnnotMirror]] of a product into the runtime [[Annots]] carrier. */
  inline def productAnnots[A]: Annots[A] =
    summonFrom:
      case am: AnnotMirror.Product[A] =>
        new Annots[A](
          AnnotMirror.materialize[am.MirroredAnnotations],
          AnnotMirror.materializeEach[am.MirroredFieldAnnotations],
          Nil
        )

  /** Materialise the [[AnnotMirror]] of a sum into the runtime [[Annots]] carrier. */
  inline def sumAnnots[A]: Annots[A] =
    summonFrom:
      case am: AnnotMirror.Sum[A] =>
        new Annots[A](
          AnnotMirror.materialize[am.MirroredAnnotations],
          Nil,
          AnnotMirror.materializeEach[am.MirroredCaseAnnotations]
        )

  inline def of[A](using m: Mirror.Of[A]): Parser[A] =
    inline m match
      case p: Mirror.ProductOf[A] => product[A](using p)
      case s: Mirror.SumOf[A]     => sum[A](using s)

  inline def product[A](using m: Mirror.ProductOf[A]): Parser[A] =
    val annots = productAnnots[A]
    val cmd = Assemble.product(
      labelsOf[m.MirroredElemLabels],
      shapesOf[m.MirroredElemTypes],
      Defaults.of[A].values,
      annots,
      arr => m.fromProduct(Tuple.fromArray(arr))
    )
    Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], annots.onType))

  inline def sum[A](using m: Mirror.SumOf[A]): Parser[A] =
    val annots = sumAnnots[A]
    val cmd = Assemble.sum(labelsOf[m.MirroredElemLabels], annots, entriesOf[m.MirroredElemTypes])
    Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], annots.onType))

  /** Reader for an enum whose cases are all parameterless, for `Reader.derived`. */
  inline def enumReader[A](using m: Mirror.SumOf[A]): Reader[A] =
    Assemble
      .enumValueReader(
        constValue[m.MirroredLabel],
        labelsOf[m.MirroredElemLabels],
        singletonValues[m.MirroredElemTypes],
        sumAnnots[A].perCase
      )
      .asInstanceOf[Reader[A]]

  // ---- fields ---------------------------------------------------------------

  inline def labelsOf[L <: Tuple]: List[String] =
    constValueTuple[L].toList.asInstanceOf[List[String]]

  inline def shapesOf[T <: Tuple]: List[Shape] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => shapeOf[h] :: shapesOf[t]

  inline def shapeOf[F]: Shape =
    inline erasedValue[F] match
      case _: Boolean   => Shape.Flag
      case _: Option[e] => optionShapeOf[e]
      case _: List[e]   => Shape.Repeated(readerFor[e], l => l)
      case _: Vector[e] => Shape.Repeated(readerFor[e], _.toVector)
      case _: Seq[e]    => Shape.Repeated(readerFor[e], l => l)
      case _            => valueOrSub[F]

  inline def optionShapeOf[E]: Shape =
    inline erasedValue[E] match
      case _: Seq[?] =>
        error("Option of a collection is not supported; use a plain collection (empty when absent)")
      case _ => valueOrSub[E].asOptional

  /** Value semantics when a `Reader` exists or every case of the enum is a singleton;
    * subcommand semantics otherwise. A parser for the field type is captured lazily
    * where possible so `@subcommands` can force command semantics at assembly time.
    */
  inline def valueOrSub[F]: Shape =
    summonFrom:
      case r: Reader[F] =>
        Shape.Value(r, lazySub[F], false)
      case s: Mirror.SumOf[F] =>
        inline if allSingletons[s.MirroredElemTypes] then
          Shape.Value(enumFieldReader[F](using s), lazySub[F], false)
        else Shape.Sub(subThunk[F](using s), false)
      case _ =>
        Shape.Value(summonInline[Reader[F]], None, false)

  inline def readerFor[E]: Reader[?] =
    summonFrom:
      case r: Reader[E] => r
      case s: Mirror.SumOf[E] =>
        inline if allSingletons[s.MirroredElemTypes] then enumFieldReader[E](using s)
        else summonInline[Reader[E]]
      case _ => summonInline[Reader[E]]

  inline def enumFieldReader[E](using s: Mirror.SumOf[E]): Reader[?] =
    Assemble.enumValueReader(
      constValue[s.MirroredLabel],
      labelsOf[s.MirroredElemLabels],
      singletonValues[s.MirroredElemTypes],
      sumAnnots[E].perCase
    )

  inline def lazySub[F]: Option[() => Parser[?]] =
    summonFrom:
      case p: Parser[F]       => Some(() => p)
      case s: Mirror.SumOf[F] => Some(() => sum[F](using s))
      case _                  => None

  inline def subThunk[F](using s: Mirror.SumOf[F]): () => Parser[?] =
    summonFrom:
      case p: Parser[F] => () => p
      case _            => () => sum[F](using s)

  // ---- sums -----------------------------------------------------------------

  // Note: `case _: (h *: t)` binders widen term-singleton types (`L.A.type` becomes
  // `L`), which breaks `ValueOf` summoning for enum cases. `Tuple.Head`/`Tuple.Tail`
  // are match types and preserve the exact element type, so sums are traversed with
  // those instead.

  inline def entriesOf[T <: Tuple]: List[SubEntry] =
    inline erasedValue[T] match
      case _: EmptyTuple    => Nil
      case _: NonEmptyTuple =>
        entryOf[Tuple.Head[T & NonEmptyTuple]] :: entriesOf[Tuple.Tail[T & NonEmptyTuple]]

  inline def entryOf[H]: SubEntry =
    summonFrom:
      case v: ValueOf[H]          => SubEntry.Leaf(v.value)
      case p: Parser[H]           => SubEntry.Node(() => p)
      case m: Mirror.ProductOf[H] => SubEntry.Node(() => product[H](using m))
      case m: Mirror.SumOf[H]     => SubEntry.Node(() => sum[H](using m))

  // ---- singleton helpers ------------------------------------------------------

  inline def allSingletons[T <: Tuple]: Boolean =
    inline erasedValue[T] match
      case _: EmptyTuple    => true
      case _: NonEmptyTuple =>
        summonFrom:
          case _: ValueOf[Tuple.Head[T & NonEmptyTuple]] => allSingletons[Tuple.Tail[T & NonEmptyTuple]]
          case _                                         => false

  inline def singletonValues[T <: Tuple]: List[Any] =
    inline erasedValue[T] match
      case _: EmptyTuple    => Nil
      case _: NonEmptyTuple =>
        summonFrom:
          case v: ValueOf[Tuple.Head[T & NonEmptyTuple]] =>
            v.value :: singletonValues[Tuple.Tail[T & NonEmptyTuple]]
          case _ =>
            error("Reader can only be derived for enums (or sealed traits) whose cases are all parameterless")
