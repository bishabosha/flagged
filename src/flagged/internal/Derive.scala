package flagged.internal

import scala.compiletime.*
import scala.deriving.Mirror
import flagged.Parser
import flagged.meta.{Ann, AnnotMirror, Defaults}

/** `Mirror`-based derivation. Structure and construction come from `Mirror`; field semantics are
  * the field parser's schema: command-shaped instances become nested subcommands (sums) or spliced
  * option groups (products), value shapes parse as option or positional values. Nothing is derived
  * across type boundaries — each enum or options group in a command tree provides its own instance.
  *
  * The only macro-backed pieces are [[Defaults]] (term-level: default values are arbitrary
  * expressions) and [[flagged.meta.AnnotMirror]] (type-level: annotations reduced to singleton
  * types, extracted here via [[flagged.meta.AnnotMirror.find]] into typed [[Annots]]).
  */
object Derive:

  // ---- parsers ----------------------------------------------------------------

  inline def of[A](using m: Mirror.Of[A]): Parser.Aux[A, Parser.Shape.Command] =
    inline m match
      case p: Mirror.ProductOf[A] => product[A](using p)
      case s: Mirror.SumOf[A]     => sum[A](using s)

  inline def product[A](using m: Mirror.ProductOf[A]): Parser.Aux[A, Parser.Shape.Command] =
    summonFrom:
      case am: AnnotMirror.Product[A] =>
        val annots = Annots.makeProduct[A](
          Annots.targetAnnotsOf[am.MirroredSelfAnnotations],
          Annots.fieldAnnotsEach[am.MirroredAnnotations]
        )
        val cmd = Assemble.product(
          labelsOf[m.MirroredElemLabels],
          fieldsOf[m.MirroredElemTypes, am.MirroredAnnotations, m.MirroredElemLabels],
          Defaults.derived[A],
          annots,
          arr => steps.result.Result.Ok(m.fromProduct(Tuple.fromArray(arr)))
        )
        Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], annots.onType))

  inline def sum[A](using m: Mirror.SumOf[A]): Parser.Aux[A, Parser.Shape.Command] =
    val annots = Annots.sumAnnots[A]
    val cmd = Assemble.sum(labelsOf[m.MirroredElemLabels], annots, entriesOf[m.MirroredElemTypes])
    Parser.make[A](cmd, Assemble.progName(constValue[m.MirroredLabel], annots.onType))

  /** Value parser for an enum whose cases are all parameterless, for `Parser.Enumerated`. */
  inline def enumParser[A](using m: Mirror.SumOf[A]): Parser.Aux[A, Parser.Shape.Value] =
    Assemble
      .enumValueParser(
        constValue[m.MirroredLabel],
        labelsOf[m.MirroredElemLabels],
        singletonValues[m.MirroredElemTypes],
        Annots.sumAnnots[A].perCase
      )
      .asInstanceOf[Parser.Aux[A, Parser.Shape.Value]]

  // ---- fields ---------------------------------------------------------------

  inline def labelsOf[L <: Tuple]: List[String] =
    constValueTuple[L].toList.asInstanceOf[List[String]]

  /** The single field rule: summon the field type's `Parser`; `Option[_]` marks it optional. The
    * parser's schema decides everything else at assembly — except that combinations already visible
    * in types (the field's annotations, `Option` wrapping, and the shape of shape-refined
    * instances, see [[Parser.Aux]]) are rejected here, at compile time. Shape-erased instances fall
    * back to construction-time validation.
    */
  inline def fieldsOf[Types <: Tuple, Slots <: Tuple, Labels <: Tuple]: List[(Parser[?], Boolean)] =
    fieldsRec[Types, Slots, Labels, Nothing, Nothing](seenTrailing = false, seenRepeatedPos = false)

  /** The field walk, folding cross-field compile-time state: shapes that are statically known
    * participate in the at-most-one-trailing and repeated-positional-last rules here; shape-erased
    * fields update nothing and are caught by the construction-time backstop.
    */
  inline def fieldsRec[Types <: Tuple, Slots <: Tuple, Labels <: Tuple, Shorts, Longs](
      inline seenTrailing: Boolean,
      inline seenRepeatedPos: Boolean
  ): List[(Parser[?], Boolean)] =
    inline erasedValue[Types] match
      case _: EmptyTuple => Nil
      case _: (f *: ft)  =>
        inline erasedValue[Slots] match
          case _: (a *: at) =>
            inline erasedValue[Labels] match
              case _: (l *: lt) =>
                inline if seenTrailing then
                  inline if staticShapeIs[f, Parser.Shape.Trailing] then
                    error("only one trailing field is supported per command")
                  else ()
                else ()
                inline if seenRepeatedPos then
                  inline if hasAnn[flagged.positional, a] then
                    error("a repeated positional must be the last positional field")
                  else ()
                else ()
                // duplicate-name detection by folding known names into union types
                // and testing membership as subtyping; only fields that surely
                // become named options fold, and only names known exactly (an
                // @name constant, or a label that is already kebab-shaped)
                inline if staticNamedOption[f] then
                  inline if hasAnn[flagged.positional, a] then
                    fieldOf[f, a] :: fieldsRec[ft, at, lt, Shorts, Longs](
                      seenTrailing = seenTrailing,
                      seenRepeatedPos = seenRepeatedPos || isRepeatedPositional[f, a]
                    )
                  else
                    checkNewShort[a, Shorts]
                    checkNewLong[a, l, Longs]
                    fieldOf[f, a] :: fieldsRec[
                      ft,
                      at,
                      lt,
                      Shorts | ShortIn[a],
                      Longs | EffLong[a, l]
                    ](
                      seenTrailing = seenTrailing,
                      seenRepeatedPos = seenRepeatedPos
                    )
                else
                  fieldOf[f, a] :: fieldsRec[ft, at, lt, Shorts, Longs](
                    seenTrailing = seenTrailing || staticShapeIs[f, Parser.Shape.Trailing],
                    seenRepeatedPos = seenRepeatedPos || isRepeatedPositional[f, a]
                  )

  /** The `@short` character claimed by the slot, or `Nothing`. */
  type ShortIn[Anns] = Anns match
    case Ann[flagged.short, args, ?] *: _ => Tuple.Head[args & NonEmptyTuple]
    case _ *: t                           => ShortIn[t]
    case EmptyTuple                       => Nothing

  /** The long name the field claims when written as a constant (`@name`); `Nothing` for
    * label-derived names, which involve kebab-casing and are checked at construction instead.
    */
  type EffLong[Anns, L] = Anns match
    case Ann[flagged.name, args, ?] *: _ => Tuple.Head[args & NonEmptyTuple]
    case _ *: t                          => EffLong[t, L]
    case EmptyTuple                      => Nothing

  /** Whether the field will certainly be a named option (statically value-ish shape). */
  transparent inline def staticNamedOption[F]: Boolean =
    summonFrom:
      case p: Parser[F] =>
        inline erasedValue[p.ShapeT] match
          case _: Parser.Shape.Value    => true
          case _: Parser.Shape.Flag     => true
          case _: Parser.Shape.Repeated => true
          case _                        => false
      case _ => false

  inline def checkNewShort[Anns, Shorts]: Unit =
    inline if hasAnn[flagged.short, Anns] then
      summonFrom:
        case _: (ShortIn[Anns] <:< Shorts) => error("duplicate short option")
        case _                             => ()
    else ()

  inline def checkNewLong[Anns, L, Longs]: Unit =
    inline if longKnown[Anns, L] then
      summonFrom:
        case _: (EffLong[Anns, L] <:< Longs) => error("duplicate option name")
        case _                               => ()
    else ()

  transparent inline def longKnown[Anns, L]: Boolean = hasAnn[flagged.name, Anns]

  /** Whether `F`'s parser is statically known to have shape `S` (false when the instance is
    * shape-erased or missing — those fall to runtime validation).
    */
  transparent inline def staticShapeIs[F, S <: Parser.Shape]: Boolean =
    summonFrom:
      case p: Parser[F] =>
        inline erasedValue[p.ShapeT] match
          case _: S => true
          case _    => false
      case _ => false

  transparent inline def isRepeatedPositional[F, Anns]: Boolean =
    inline if hasAnn[flagged.positional, Anns] then staticShapeIs[F, Parser.Shape.Repeated]
    else false

  /** Whether annotation slot `Anns` contains an `A` — a compile-time constant. */
  transparent inline def hasAnn[A <: scala.annotation.Annotation, Anns]: Boolean =
    inline erasedValue[Anns] match
      case _: EmptyTuple          => false
      case _: (Ann[A, ?, ?] *: _) => true
      case _: (_ *: t)            => hasAnn[A, t]

  /** Whether the slot contains a specific annotation *application*, e.g. `@short('h')` — decidable
    * because `Ann` is invariant with constant arguments.
    */
  transparent inline def hasAnnApplied[Applied, Anns]: Boolean =
    inline erasedValue[Anns] match
      case _: EmptyTuple     => false
      case _: (Applied *: _) => true
      case _: (_ *: t)       => hasAnnApplied[Applied, t]

  inline def fieldOf[F, Anns]: (Parser[?], Boolean) =
    inline if hasAnnApplied[Ann[flagged.short, 'h' *: EmptyTuple, ?], Anns] then
      error("short option 'h' is reserved for help")
    else inline if hasAnn[flagged.positional, Anns] then
      inline if hasAnn[flagged.short, Anns] then error("@short cannot be combined with @positional")
      else fieldOfChecked[F, Anns]
    else inline if hasAnnApplied[Ann[flagged.name, "help" *: EmptyTuple, ?], Anns] then
      error("option name 'help' is reserved")
    else fieldOfChecked[F, Anns]

  // Instance selection is a single plain summon, so normal given priority is
  // untouched (a user's shape-erased `given Parser[List[Int]] = ...` shadows the
  // library instance). But the summonFrom *binder* receives the found given's precise
  // type, so when that instance is shape-refined (all library instances and anything
  // built by the Parser constructors without an ascription, and everything derived
  // through the Parser.Command / Parser.Enumerated witnesses, whose bridging givens
  // are library-declared with refined types), its `ShapeT` is concrete and the
  // annotation x shape combination is checked at compile time — an abstract `ShapeT`
  // (a given explicitly ascribed to plain `Parser[X]`) simply falls through to the
  // construction-time backstop in `Assemble.resolveField`.
  inline def fieldOfChecked[F, Anns]: (Parser[?], Boolean) =
    inline erasedValue[F] match
      case _: Option[e] => (summonChecked[e, Anns](optional = true), true)
      case _            => (summonChecked[F, Anns](optional = false), false)

  // note: the summon must go through this helper — a lowercase binder like `e` used
  // inside a summonFrom pattern would bind a fresh pattern type variable (an
  // unconstrained query) instead of referring to the enclosing inline-match binder
  inline def summonChecked[F, Anns](inline optional: Boolean): Parser[?] =
    summonFrom:
      case p: Parser[F] =>
        checkShape[p.ShapeT, Anns](optional)
        p
      case _ => summonInline[Parser[F]] // fails with Parser's missing-instance guidance

  /** Compile-time half of the shape x annotation matrix, for statically known shapes. An abstract
    * `S` (shape-erased instance) matches none of the cases and falls to the wildcard: the inliner
    * skips unprovable type tests as long as a default remains reachable.
    */
  inline def checkShape[S <: Parser.Shape, Anns](inline optional: Boolean): Unit =
    inline erasedValue[S] match
      case _: Parser.Shape.ValuedFlag => () // usable everywhere: has the explicit-value form
      case _: Parser.Shape.Flag       =>
        inline if optional then
          error("a flag Parser without a value parser cannot be used inside Option")
        else inline if hasAnn[flagged.positional, Anns] then
          error("a flag Parser without a value parser cannot be used positionally")
        else ()
      case _: Parser.Shape.Repeated =>
        inline if optional then
          error("Option of a repeated Parser is not supported: the plain type is empty when absent")
        else ()
      case _: Parser.Shape.Trailing =>
        inline if hasAnn[flagged.positional, Anns] then
          error("@positional cannot be combined with a trailing field")
        else inline if hasAnn[flagged.short, Anns] then
          error("@short cannot be combined with a trailing field")
        else ()
      case _: Parser.Shape.Command =>
        inline if hasAnn[flagged.positional, Anns] then
          error("@positional cannot be combined with a command-shaped Parser")
        else ()
      case _ => () // Value, Flag, or a shape-erased instance

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
