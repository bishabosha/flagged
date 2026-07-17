package claw.internal

import scala.compiletime.*
import scala.deriving.Mirror
import claw.Parser
import claw.meta.{Defaults, AnnotMirror}

/** `Mirror`-based derivation. Structure and construction come from `Mirror`; field semantics are
  * the field parser's schema: command-shaped instances become nested subcommands (sums) or spliced
  * option groups (products), value shapes parse as option or positional values. Nothing is derived
  * across type boundaries — each enum or options group in a command tree provides its own instance.
  *
  * The only macro-backed pieces are [[Defaults]] (term-level: default values are arbitrary
  * expressions) and [[AnnotMirror]] (type-level: annotations reduced to singleton types, extracted
  * here via [[AnnotMirror.find]] into typed [[Annots]]).
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
    TargetAnnots(
      AnnotMirror.find[claw.name, Anns].map(_.value),
      AnnotMirror.find[claw.help, Anns].map(_.value)
    )

  inline def fieldAnnotsOf[Anns]: FieldAnnots =
    FieldAnnots(
      AnnotMirror.find[claw.name, Anns].map(_.value),
      AnnotMirror.find[claw.short, Anns].map(_.value),
      AnnotMirror.find[claw.help, Anns].map(_.value),
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
    val cmd    = Assemble.product(
      labelsOf[m.MirroredElemLabels],
      fieldsOf[m.MirroredElemTypes],
      Defaults.derived[A],
      annots,
      arr => m.fromProduct(Tuple.fromArray(arr))
    )
    Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], annots.onType))

  inline def sum[A](using m: Mirror.SumOf[A]): Parser[A] =
    val annots = sumAnnots[A]
    val cmd = Assemble.sum(labelsOf[m.MirroredElemLabels], annots, entriesOf[m.MirroredElemTypes])
    Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], annots.onType))

  /** Value parser for an enum whose cases are all parameterless, for `Parser.Enumerated`. */
  inline def enumParser[A](using m: Mirror.SumOf[A]): Parser[A] =
    Assemble
      .enumValueParser(
        constValue[m.MirroredLabel],
        labelsOf[m.MirroredElemLabels],
        singletonValues[m.MirroredElemTypes],
        sumAnnots[A].perCase
      )
      .asInstanceOf[Parser[A]]

  // ---- fields ---------------------------------------------------------------

  inline def labelsOf[L <: Tuple]: List[String] =
    constValueTuple[L].toList.asInstanceOf[List[String]]

  /** The single field rule: summon the field type's `Parser`; `Option[_]` marks it optional. The
    * parser's schema decides everything else at assembly.
    */
  inline def fieldsOf[T <: Tuple]: List[(Parser[?], Boolean)] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => fieldOf[h] :: fieldsOf[t]

  inline def fieldOf[F]: (Parser[?], Boolean) =
    inline erasedValue[F] match
      case _: Option[e] => (summonInline[Parser[e]], true)
      case _            => (summonInline[Parser[F]], false)

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

  /** One case of the sum being derived. Singleton and product cases belong to the sum's own
    * declaration and are handled in place; a case that is itself a sum is a separate hierarchy and
    * must provide its own `Parser` instance.
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
            error(
              "Parser.Enumerated requires an enum (or sealed trait) whose cases are all parameterless"
            )
