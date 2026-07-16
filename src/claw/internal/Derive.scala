package claw.internal

import scala.compiletime.*
import scala.deriving.Mirror
import claw.{Parser, Reader}

/** `Mirror`-based derivation. Structure and construction come from `Mirror`; field
  * semantics are instance-driven: a `Parser` given for the field's type makes it a
  * group of subcommands, otherwise a `Reader` given parses it as a value (the reader's
  * schema decides whether it is single or repeated). Nothing is derived across type
  * boundaries — each enum in a command tree provides its own instances.
  *
  * The only macro-backed pieces are [[Defaults]] (term-level: default values are
  * arbitrary expressions) and [[AnnotMirror]] (type-level: annotations reduced to
  * singleton types, extracted here via [[AnnotMirror.find]] into typed [[Annots]]).
  */
object Derive:

  /** Extract claw's annotations for a product into typed records. */
  inline def productAnnots[A]: Annots.Product[A] =
    summonFrom:
      case am: AnnotMirror.Product[A] =>
        Annots.Product[A](
          targetAnnotsOf[am.MirroredSelfAnnotations],
          fieldAnnotsEach[am.MirroredAnnotations]
        )

  /** Extract claw's annotations for a sum into typed records. */
  inline def sumAnnots[A]: Annots.Sum[A] =
    summonFrom:
      case am: AnnotMirror.Sum[A] =>
        Annots.Sum[A](
          targetAnnotsOf[am.MirroredSelfAnnotations],
          targetAnnotsEach[am.MirroredAnnotations]
        )

  inline def targetAnnotsOf[Anns]: TargetAnnots =
    TargetAnnots(AnnotMirror.find[claw.name, Anns], AnnotMirror.find[claw.help, Anns])

  inline def fieldAnnotsOf[Anns]: FieldAnnots =
    FieldAnnots(
      AnnotMirror.find[claw.name, Anns],
      AnnotMirror.find[claw.short, Anns],
      AnnotMirror.find[claw.help, Anns],
      AnnotMirror.find[claw.positional, Anns].isDefined
    )

  inline def fieldAnnotsEach[Slots]: List[FieldAnnots] =
    inline erasedValue[Slots] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => fieldAnnotsOf[h] :: fieldAnnotsEach[t]

  inline def targetAnnotsEach[Slots]: List[TargetAnnots] =
    inline erasedValue[Slots] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => targetAnnotsOf[h] :: targetAnnotsEach[t]

  // ---- parsers ----------------------------------------------------------------

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
      case _: Option[e] => fieldShape[e].asOptional
      case _            => fieldShape[F]

  /** The single field rule: a `Parser` instance makes the field a subparser; otherwise
    * a `Reader` instance parses it as a value, with the reader's schema encoding
    * whether occurrences repeat. Neither in scope is a compile error.
    */
  inline def fieldShape[F]: Shape =
    summonFrom:
      case p: Parser[F] => Shape.Sub(() => p, false)
      case r: Reader[F] => Shape.Value(r, false)
      case _            => Shape.Value(summonInline[Reader[F]], false)

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

  /** One case of the sum being derived. Singleton and product cases belong to the
    * sum's own declaration and are handled in place; a case that is itself a sum is a
    * separate hierarchy and must provide its own `Parser` instance.
    */
  inline def entryOf[H]: SubEntry =
    summonFrom:
      case v: ValueOf[H]          => SubEntry.Leaf(v.value)
      case p: Parser[H]           => SubEntry.Node(() => p)
      case m: Mirror.ProductOf[H] => SubEntry.Node(() => product[H](using m))
      case _                      => SubEntry.Node(() => summonInline[Parser[H]])

  // ---- singleton helpers ------------------------------------------------------

  inline def singletonValues[T <: Tuple]: List[Any] =
    inline erasedValue[T] match
      case _: EmptyTuple    => Nil
      case _: NonEmptyTuple =>
        summonFrom:
          case v: ValueOf[Tuple.Head[T & NonEmptyTuple]] =>
            v.value :: singletonValues[Tuple.Tail[T & NonEmptyTuple]]
          case _ =>
            error("Reader can only be derived for enums (or sealed traits) whose cases are all parameterless")
